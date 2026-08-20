package dev.undine.infrastructure.git.ref

import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import dev.undine.domain.RefName
import dev.undine.domain.Tag
import dev.undine.domain.UndineException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException

/**
 * JGit 실패를 도메인 예외로 번역한다. [UndineException] 은 여기 걸리지 않고 그대로 올라간다 —
 * 이미 사용자에게 보여줄 형태이기 때문이다.
 *
 * [CheckoutConflictException] 만 따로 번역한다. 사용자가 워킹트리를 정리하면 해결되는 실패라
 * "앱 버그" 를 뜻하는 [UndineException.GitOperationFailed] 로 뭉뚱그리면 안내가 틀린다.
 */
internal inline fun <T> translatingGitFailure(operation: String, block: () -> T): T =
    try {
        block()
    } catch (gitFailure: GitAPIException) {
        throw domainFailureOf(operation, gitFailure)
    } catch (ioFailure: IOException) {
        throw UndineException.GitOperationFailed(operation, ioFailure)
    }

internal fun domainFailureOf(operation: String, gitFailure: GitAPIException): UndineException =
    when (gitFailure) {
        is CheckoutConflictException ->
            UndineException.DirtyWorkingTree(gitFailure.conflictingPaths.orEmpty().sorted())

        else -> UndineException.GitOperationFailed(operation, gitFailure)
    }

/**
 * 브랜치 ref 를 도메인 [Branch] 로 옮긴다. [currentFullRef] 는 호출부가 한 번만 읽어 넘긴다 —
 * 브랜치마다 `HEAD` 를 다시 읽으면 목록 조회가 ref 수만큼 파일을 두드린다.
 *
 * `upstream` 은 설정된 값([BranchConfig])을, `ahead`·`behind` 는 실제 계산 결과
 * ([BranchTrackingStatus])를 쓴다. 업스트림이 설정돼 있어도 원격 추적 ref 를 아직 fetch 하지
 * 않았으면 개수는 0 이다 — 계산할 근거가 없는 것을 추측하지 않는다.
 */
internal fun Repository.toBranch(ref: Ref, currentFullRef: String?): Branch {
    val fullRef = ref.name
    val isRemote = fullRef.startsWith(REMOTE_BRANCH_PREFIX)
    val shortName = Repository.shortenRefName(fullRef)
    val target = ref.objectId ?: throw UndineException.GitOperationFailed("ref.resolve")
    val trackingBranch = if (isRemote) null else BranchConfig(config, shortName).trackingBranch
    val trackingStatus = if (isRemote) null else BranchTrackingStatus.of(this, shortName)
    return Branch(
        name = RefName(shortName),
        target = CommitId.of(target.name),
        isCurrent = fullRef == currentFullRef,
        isRemote = isRemote,
        upstream = trackingBranch?.let { RefName(Repository.shortenRefName(it)) },
        ahead = trackingStatus?.aheadCount ?: 0,
        behind = trackingStatus?.behindCount ?: 0,
    )
}

/**
 * 태그 ref 를 도메인 [Tag] 로 옮긴다. annotated 태그는 ref 가 태그 객체를 가리키므로
 * `target` 은 peel 한 커밋이고, lightweight 태그는 ref 가 곧 커밋이다.
 */
internal fun RevWalk.toTag(ref: Ref): Tag {
    val name = RefName(Repository.shortenRefName(ref.name))
    val objectId = ref.objectId ?: throw UndineException.GitOperationFailed("tag.resolve")
    return when (val tagged = parseAny(objectId)) {
        is RevTag -> Tag(
            name = name,
            target = CommitId.of(peel(tagged).id.name),
            isAnnotated = true,
            message = tagged.fullMessage,
            tagger = tagged.taggerIdent?.let { Person(it.name, it.emailAddress) },
        )

        else -> Tag(
            name = name,
            target = CommitId.of(tagged.id.name),
            isAnnotated = false,
            message = null,
            tagger = null,
        )
    }
}

internal fun RevWalk.commitOf(id: CommitId): RevCommit =
    try {
        parseCommit(ObjectId.fromString(id.value))
    } catch (ignoredMissing: MissingObjectException) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, id.value)
    }

/**
 * 체크아웃 전 더티 가드. untracked 파일은 git 과 같이 체크아웃을 막지 않으므로
 * [org.eclipse.jgit.api.Status.getUncommittedChanges] 로 추적 중인 변경만 본다.
 */
internal fun Git.rejectIfDirty() {
    val uncommittedPaths = status().call().uncommittedChanges.sorted()
    if (uncommittedPaths.isNotEmpty()) throw UndineException.DirtyWorkingTree(uncommittedPaths)
}
