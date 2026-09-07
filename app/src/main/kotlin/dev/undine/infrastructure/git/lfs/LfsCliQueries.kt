package dev.undine.infrastructure.git.lfs

import dev.undine.domain.lfs.LfsCommandOutcome
import dev.undine.domain.lfs.LfsCommandRunner
import dev.undine.domain.lfs.LfsDownloadEstimate
import dev.undine.domain.lfs.LfsLockState
import dev.undine.domain.lfs.LfsObject
import dev.undine.domain.lfs.LfsObjectState
import dev.undine.domain.lfs.LfsResult
import dev.undine.domain.lfs.LfsVersion
import java.nio.file.Path

/**
 * `git-lfs` CLI 를 거치는 조회 넷.
 *
 * Gateway 에서 떼어 둔 이유는 두 축이 서로 독립이기 때문이다 — 이쪽은 **프로세스와 출력 해석**,
 * [LfsAttributesEditor] 는 **파일 원문 보존**이다. 한 클래스에 섞으면 어느 쪽을 고쳐도 다른 쪽을
 * 같이 읽어야 한다.
 *
 * @param workingDirectory 실행할 워킹트리. 저장소 락을 쥐지 않은 채 호출되도록 값이 아니라
 *   함수로 받는다 — CLI 는 분 단위로 걸릴 수 있어 락 안에서 돌리면 모든 Git 접근이 멈춘다.
 */
internal class LfsCliQueries(
    private val runner: LfsCommandRunner,
    private val workingDirectory: suspend () -> Path,
) {

    suspend fun version(): LfsResult<LfsVersion> =
        query(LfsCommand.VERSION) { output -> LfsCliOutput.versionOf(output) }

    suspend fun objects(): LfsResult<List<LfsObject>> =
        query(LfsCommand.LS_FILES) { output -> LfsCliOutput.objectsOf(output) }

    /** 크기를 읽지 못하면 빈 추정이 아니라 파싱 실패다 — 틀린 크기로 다운로드를 승인시키지 않는다. */
    suspend fun pendingDownload(): LfsResult<LfsDownloadEstimate> =
        query(LfsCommand.LS_FILES_WITH_SIZE) { output ->
            LfsCliOutput.sizedObjectsOf(output)?.let { sized ->
                val pending = sized.filter { entry -> entry.entry.state == LfsObjectState.POINTER_ONLY }
                LfsDownloadEstimate(
                    objects = pending.map { entry -> entry.entry },
                    totalBytes = pending.sumOf { entry -> entry.bytes },
                )
            }
        }

    suspend fun download(onProgress: (String) -> Unit): LfsResult<Unit> =
        query(LfsCommand.FETCH, onProgress) { Unit }

    /**
     * 잠금은 서버가 지원할 때만 쓸 수 있는 부가 기능이라, **서버가 미지원이라고 말한 경우에만**
     * 조회 실패가 아니라 비활성으로 보고한다. 사유는 그대로 실어 왜 못 쓰는지가 사라지지 않게 한다.
     *
     * 그 밖의 0 이 아닌 종료는 [LfsResult.CommandFailed] 로 그대로 올린다 — 모든 실패를 비활성으로
     * 접으면 "서버가 지원하지 않음" 과 "조회가 실패함" 이 한 값이 되고, 사용자는 빈 잠금 목록을
     * 보고 잠금이 없다고 믿는다.
     */
    suspend fun locks(): LfsResult<LfsLockState> {
        val outcome = runner.run(LfsCommand.LOCKS.arguments, workingDirectory()) { }
        return when (outcome) {
            LfsCommandOutcome.NotInstalled -> LfsResult.NotInstalled
            is LfsCommandOutcome.StartFailed -> LfsResult.StartFailed(outcome.detail)
            is LfsCommandOutcome.Completed -> outcome.lockState()
        }
    }

    private fun LfsCommandOutcome.Completed.lockState(): LfsResult<LfsLockState> = when {
        exitCode == 0 -> LfsCliOutput.locksOf(standardOutput)
            ?.let { locks -> LfsResult.Available(LfsLockState.Active(locks)) }
            ?: LfsResult.OutputUnrecognized(standardOutput)

        LfsCliOutput.declaresLockingUnsupported(failureDetail()) ->
            LfsResult.Available(LfsLockState.Inactive(failureDetail()))

        else -> LfsResult.CommandFailed(exitCode, failureDetail())
    }

    private suspend fun <T> query(
        command: LfsCommand,
        onOutputLine: (String) -> Unit = {},
        parse: (String) -> T?,
    ): LfsResult<T> {
        val outcome = runner.run(command.arguments, workingDirectory(), onOutputLine)
        return when (outcome) {
            LfsCommandOutcome.NotInstalled -> LfsResult.NotInstalled
            is LfsCommandOutcome.StartFailed -> LfsResult.StartFailed(outcome.detail)
            is LfsCommandOutcome.Completed -> if (outcome.exitCode == 0) {
                parse(outcome.standardOutput)
                    ?.let { value -> LfsResult.Available(value) }
                    ?: LfsResult.OutputUnrecognized(outcome.standardOutput)
            } else {
                LfsResult.CommandFailed(outcome.exitCode, outcome.failureDetail())
            }
        }
    }
}

/** 실패 사유는 표준 오류가 먼저다. 비어 있으면 표준 출력이라도 실어 사유가 사라지지 않게 한다. */
private fun LfsCommandOutcome.Completed.failureDetail(): String =
    standardError.ifBlank { standardOutput }
