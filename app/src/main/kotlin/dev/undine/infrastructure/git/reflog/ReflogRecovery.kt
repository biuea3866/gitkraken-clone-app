package dev.undine.infrastructure.git.reflog

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.RefMoveConfirmation
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository

private const val BRANCH_EXISTS = "같은 이름의 브랜치가 이미 있습니다"
private const val REF_MISSING = "옮길 참조가 없습니다"
private const val MOVE_REJECTED =
    "확인 뒤 참조가 움직여 옮기지 않았습니다. 대상을 다시 조회한 뒤 확인하세요"
private const val MOVE_REFLOG_MESSAGE = "undine: reflog recover"

/** 옮기기가 실제로 성사된 결과. 그 밖의 값은 ref 를 바꾸지 못했다는 뜻이다. */
private val ACCEPTED_MOVE_RESULTS = setOf(
    RefUpdate.Result.FORCED,
    RefUpdate.Result.FAST_FORWARD,
    RefUpdate.Result.NEW,
    RefUpdate.Result.NO_CHANGE,
)

/** 잃어버린 지점을 [target] 방식으로 되살리고 만들어진 참조를 돌려준다. */
internal fun Git.recoverAt(commit: ObjectId, target: RecoveryTarget): RefName = when (target) {
    is RecoveryTarget.NewBranch -> createBranchAt(target.name, commit)
    is RecoveryTarget.MoveExisting -> moveRefTo(target, commit)
}

/**
 * 되살릴 커밋이 실제로 객체 DB 에 있는지 확인한다.
 *
 * @throws UndineException.NotFound 이미 정리(gc)돼 사라졌을 때 — 복구 자체가 불가능하다는 뜻이다.
 */
internal fun Repository.requireCommit(commit: CommitId): ObjectId {
    val id = resolve(commit.value)
    if (id == null || !objectDatabase.has(id)) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
    }
    return id
}

/** 새 브랜치를 그 지점에 만든다. 이름이 겹치면 만들지 않는다 — 덮어쓰면 또 잃는다. */
private fun Git.createBranchAt(name: RefName, commit: ObjectId): RefName {
    if (repository.findRef(name.value) != null) {
        throw UndineException.StateViolation("$BRANCH_EXISTS: ${name.value}")
    }
    branchCreate().setName(name.value).setStartPoint(commit.name).call()
    return name
}

/**
 * 기존 참조를 그 지점으로 옮긴다.
 *
 * 옮기기 직전에 확인 값을 재검증한다 — 조회 뒤 ref 가 움직였다면 사용자는 **다른 커밋이 밀려난다는
 * 것을 모르고** 확인한 것이다. 판정은 도메인의 [RefMoveConfirmation.validateFor] 가 하고,
 * 재검증과 갱신 사이의 틈은 [moveRefTo] 의 비교-교환이 막는다.
 */
private fun Git.moveRefTo(target: RecoveryTarget.MoveExisting, commit: ObjectId): RefName {
    val current = repository.findRef(target.name.value)
    val displaced = current?.objectId
    if (current == null || displaced == null) {
        throw UndineException.StateViolation("$REF_MISSING: ${target.name.value}")
    }
    target.confirmation.validateFor(CommitId.of(displaced.name))
    repository.moveRefTo(current.name, displaced, commit)
    return target.name
}

/**
 * [refName] 을 [commit] 으로 옮기되, 방금 읽은 [displaced] 를 expected-old-object-id 로 걸어
 * 비교-교환(CAS)으로 갱신한다.
 *
 * 확인 값 재검증만으로는 **재검증과 갱신 사이**가 비어 있다 — 그 틈에 외부 git 프로세스나 연결된
 * 다른 worktree 가 ref 를 옮기면 force 갱신이 사용자가 본 적 없는 커밋을 밀어낸다. 잃은 커밋을
 * 되찾으러 온 사용자가 새로 잃는 경로라 갱신 자체가 막고 재조회를 요구해야 한다.
 *
 * 기록은 [RefUpdate.setForceRefLog] 로 강제한다. `core.logAllRefUpdates=false` 이거나 대상 ref 의
 * reflog 파일이 아직 없으면 JGit 은 기록을 건너뛰는데, 그러면 이 이동이 밀어낸 [displaced] 를
 * 가리키는 흔적이 어디에도 남지 않아 다음 gc 때 실제로 사라진다. 복구 연산이 새로운 유실을 만드는
 * 경로이므로 저장소 설정과 무관하게 남긴다.
 *
 * @throws UndineException.StateViolation 갱신 시점의 커밋이 [displaced] 와 달라 거부됐을 때
 */
internal fun Repository.moveRefTo(refName: String, displaced: ObjectId, commit: ObjectId) {
    val update = updateRef(refName)
    update.setExpectedOldObjectId(displaced)
    update.setNewObjectId(commit)
    update.isForceUpdate = true
    update.setRefLogMessage(MOVE_REFLOG_MESSAGE, false)
    update.setForceRefLog(true)
    val result = update.update()
    if (result !in ACCEPTED_MOVE_RESULTS) {
        throw UndineException.StateViolation("$MOVE_REJECTED ($result)")
    }
}
