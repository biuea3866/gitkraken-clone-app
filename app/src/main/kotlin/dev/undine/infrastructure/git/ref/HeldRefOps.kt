package dev.undine.infrastructure.git.ref

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.UndineException
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk

internal const val EXPECTED_TARGET_MISMATCH = "기대한 위치와 달라 참조를 옮기지 않았습니다"
private const val CURRENT_BRANCH_POINTER_MOVE =
    "현재 체크아웃된 브랜치는 포인터만 옮길 수 없습니다 — reset 으로 워킹트리까지 맞추세요"
private const val ANNOTATED_TAG_MOVE = "annotated 태그는 옮기면 메시지·tagger 를 잃어 옮기지 않았습니다"
private const val REF_UPDATE_REJECTED = "참조 갱신이 거부됐습니다"

/**
 * ref-update 가 성공으로 인정하는 결과. [RefUpdate.Result.NO_CHANGE] 도 성공이다 —
 * 이미 그 위치면 옮길 것이 없을 뿐이고, 기대 위치 검사는 이미 통과했다.
 */
private val ACCEPTED_REF_UPDATES = setOf(
    RefUpdate.Result.NEW,
    RefUpdate.Result.FORCED,
    RefUpdate.Result.FAST_FORWARD,
    RefUpdate.Result.NO_CHANGE,
)

/*
 * 이미 [dev.undine.infrastructure.git.repository.GitAccess] 의 임계 구역 안에서, 락을 쥔 핸들로
 * 호출하는 내부 경로다 (결정 G4). 여기서는 락도 스레드 전환도 다시 하지 않는다 — 그 책임은
 * 구역을 연 쪽에 있다. 조작 로직을 두 벌로 만들지 않으려고 Gateway 구현과 이 경로가 같은 함수를 쓴다.
 */

/** [RefGatewayImpl.checkout] 의 본체. 원격 ref 는 추적 로컬 브랜치로 옮겨 detached HEAD 를 피한다. */
internal fun Git.checkoutHeld(ref: RefName, force: Boolean) {
    val candidates = validatedCheckoutCandidates(ref)
    val resolved = candidates.firstNotNullOfOrNull { repository.exactRef(it) }
        ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, ref.value)
    if (!force) rejectIfDirty()
    if (resolved.name.startsWith(REMOTE_BRANCH_PREFIX)) {
        checkoutTracking(resolved, force)
    } else {
        checkout().setName(resolved.name).setForced(force).call()
    }
}

/**
 * 브랜치를 [expected] 일 때만 [to] 로 옮긴다. 현재 체크아웃된 브랜치는 거부한다 —
 * 포인터만 옮기면 워킹트리가 HEAD 와 어긋난 채 남는다.
 */
internal fun Git.moveBranchHeld(branch: RefName, to: CommitId, expected: CommitId) {
    val fullRef = validatedBranchRef(branch)
    val current = repository.requireRefTarget(fullRef, branch)
    if (repository.fullBranch == fullRef) {
        throw UndineException.StateViolation("$CURRENT_BRANCH_POINTER_MOVE: ${branch.value}")
    }
    requireExpectedTarget(branch, CommitId.of(current.name), expected)
    repository.updateRefHeld(fullRef, branch, requireCommitObject(to), current)
}

/** 태그를 [expected] 일 때만 [to] 로 옮긴다. annotated 태그는 잃는 것이 있어 거부한다. */
internal fun Git.moveTagHeld(tag: RefName, to: CommitId, expected: CommitId) {
    val fullRef = validatedTagRef(tag)
    val current = repository.requireRefTarget(fullRef, tag)
    if (repository.isAnnotatedTag(current)) {
        throw UndineException.StateViolation("$ANNOTATED_TAG_MOVE: ${tag.value}")
    }
    requireExpectedTarget(tag, CommitId.of(current.name), expected)
    repository.updateRefHeld(fullRef, tag, requireCommitObject(to), current)
}

/**
 * 기대 위치와 실제 위치를 대조한다. 어긋나면 **바꾸지 않고** 사유를 남긴다 —
 * 강제로 옮기면 그 ref 로만 도달하던 커밋을 잃는다 (결정 G2).
 */
internal fun requireExpectedTarget(name: RefName, actual: CommitId, expected: CommitId) {
    if (actual == expected) return
    throw UndineException.StateViolation(
        "$EXPECTED_TARGET_MISMATCH: ${name.value} (기대=${expected.value}, 실제=${actual.value})",
    )
}

/**
 * ref-update 잠금 안에서 [expectedOld] 일 때만 [to] 로 옮긴다 — JGit `RefUpdate` 의 조건부 갱신이다.
 * 위의 [requireExpectedTarget] 이 사용자에게 보여줄 사유를 만들고, 이 검사는 갱신 자체를 잠근다.
 */
internal fun Repository.updateRefHeld(fullRef: String, name: RefName, to: ObjectId, expectedOld: ObjectId) {
    val update = updateRef(fullRef).apply {
        setNewObjectId(to)
        setExpectedOldObjectId(expectedOld)
        // 되감기(non-fast-forward)도 요청된 이동이다 — 기대 위치 검사가 이미 안전을 담보한다.
        setForceUpdate(true)
    }
    val result = update.update()
    if (result !in ACCEPTED_REF_UPDATES) {
        throw UndineException.StateViolation("$REF_UPDATE_REJECTED(${result.name}): ${name.value}")
    }
}

/**
 * 커밋 존재까지 확인한다. `resolve` 는 완전한 40자 hex 를 존재 여부와 무관하게 그대로 돌려주므로,
 * 확인하지 않으면 "없는 커밋" 이 한참 뒤 JGit 내부 실패로 뭉뚱그려진다.
 */
internal fun Git.requireCommitObject(commit: CommitId): ObjectId {
    val id = repository.resolve(commit.value)
    if (id == null || !repository.objectDatabase.has(id)) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
    }
    return id
}

/**
 * 지금의 기준 상태를 **락을 쥔 채** 읽는다. 변경 연산이 자기 임계 구역 안에서, 변경이 끝난 직후
 * 불러 결과에 실어 준다 (UND-73) — 호출자가 변경 뒤에 따로 읽으면 그 사이의 다른 조작까지 반영된다.
 *
 * 체크아웃된 **로컬 브랜치**가 없으면(detached HEAD·커밋이 없는 저장소) 브랜치도 HEAD 도 없는
 * 상태다. `RefGateway.listBranches()` 로 같은 값을 유도하는 `currentBaseline()` 과 결과가 같아야
 * 비교가 성립하므로, 판정 기준(로컬 브랜치 prefix + 그 ref 의 target)을 그것과 맞춘다.
 */
internal fun Repository.baselineHeld(): RepositoryBaseline {
    val fullRef = fullBranch?.takeIf { it.startsWith(LOCAL_BRANCH_PREFIX) }
    // 커밋이 하나도 없는 저장소는 HEAD 가 아직 없는 브랜치를 가리킨다 — 그 브랜치는 목록에도 없다.
    val target = fullRef?.let { exactRef(it)?.objectId }
    return if (fullRef == null || target == null) {
        RepositoryBaseline(branch = null, head = null)
    } else {
        RepositoryBaseline(
            branch = RefName(Repository.shortenRefName(fullRef)),
            head = CommitId.of(target.name),
        )
    }
}

internal fun Repository.requireRefTarget(fullRef: String, name: RefName): ObjectId =
    exactRef(fullRef)?.objectId
        ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, name.value)

private fun Repository.isAnnotatedTag(target: ObjectId): Boolean =
    RevWalk(this).use { walk -> walk.parseAny(target) is RevTag }

/**
 * 원격 ref 를 그대로 체크아웃하면 detached HEAD 가 되어 사용자가 커밋을 잃기 쉽다.
 * 같은 이름의 로컬 브랜치가 이미 있으면 그것으로, 없으면 원격을 추적하는 로컬 브랜치를 만들어 옮긴다.
 */
private fun Git.checkoutTracking(remoteRef: Ref, force: Boolean) {
    val localName = localTrackingNameOf(remoteRef.name)
    val existingLocal = repository.exactRef(LOCAL_BRANCH_PREFIX + localName)
    if (existingLocal != null) {
        checkout().setName(existingLocal.name).setForced(force).call()
        return
    }
    checkout()
        .setName(localName)
        .setCreateBranch(true)
        .setStartPoint(remoteRef.name)
        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
        .setForced(force)
        .call()
}
