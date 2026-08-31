package dev.undine.infrastructure.git.cherrypick

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import dev.undine.domain.cherrypick.CherryPickStep
import dev.undine.infrastructure.git.ref.baselineHeld
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.api.CherryPickResult as JGitCherryPickResult

/**
 * [CherryPickGatewayImpl.apply] 의 본체이자, 이미 `GitAccess` 의 임계 구역 안에서 락을 쥔 채
 * 호출하는 내부 경로다 (결정 G4) — 체크아웃과 한 구역에서 돌려야 하는
 * `WorktreeOpsGateway.runOnBranch` 가 쓴다. 락도 스레드 전환도 여기서 다시 하지 않는다.
 */
internal fun Git.applyHeld(commit: CommitId, recordOrigin: Boolean): CherryPickStep {
    val before = repository.resolve(Constants.HEAD)
    repository.rememberStartPoint()
    val picked = cherryPick()
        .include(repository.requireObject(commit))
        .call()
    return when (picked.status) {
        JGitCherryPickResult.CherryPickStatus.OK -> stepFor(before, commit, recordOrigin)

        JGitCherryPickResult.CherryPickStatus.CONFLICTING -> CherryPickStep.Conflicted(conflictedPaths())

        else -> throw UndineException.GitOperationFailed(OPERATION_APPLY)
    }
}

/**
 * HEAD 가 움직였는지로 "만들어졌다" 와 "적용할 것이 없었다" 를 가른다.
 *
 * JGit 은 두 경우 모두 `OK` 를 주므로 상태만으로는 구분되지 않는다 — 빈 커밋을 실패로 처리하면
 * 사용자가 고칠 것이 없는데 고치려 하게 된다.
 */
private fun Git.stepFor(before: ObjectId?, origin: CommitId, recordOrigin: Boolean): CherryPickStep {
    val after = repository.resolve(Constants.HEAD)
    if (after == null || after == before) return CherryPickStep.Empty
    val created = if (recordOrigin) recordOrigin(CommitId.of(after.name), origin).name else after.name
    // 되돌리기 재료는 적용과 **같은 임계 구역**에서 캡처한다 — [before] 는 이 단계 직전 HEAD 다 (UND-73).
    return CherryPickStep.Created(
        commit = CommitId.of(created),
        previousHead = before?.let { CommitId.of(it.name) },
        baseline = repository.baselineHeld(),
    )
}

/** 만들어진 커밋의 메시지 끝에 원본 해시 줄을 붙인다 (`git cherry-pick -x` 상당). */
private fun Git.recordOrigin(created: CommitId, origin: CommitId): RevCommit =
    commit()
        .setAmend(true)
        .setMessage(repository.messageOf(created).withOriginNote(origin))
        .call()

private fun String.withOriginNote(origin: CommitId): String =
    "${trimEnd()}\n\n$ORIGIN_NOTE_PREFIX$origin)\n"

/**
 * 시작 전 HEAD 를 `ORIG_HEAD` 에 남긴다 — 중단이 되돌릴 지점이다. git 도 cherry-pick 시작 시 같은
 * 참조를 갱신한다. 커밋이 없는 저장소는 되돌릴 지점 자체가 없어 남기지 않는다.
 */
private fun Repository.rememberStartPoint() {
    resolve(Constants.HEAD)?.let { writeOrigHead(it) }
}
