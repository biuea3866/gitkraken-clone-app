package dev.undine.infrastructure.git.lfs

import dev.undine.domain.lfs.LfsCommandRunner
import dev.undine.domain.lfs.LfsDownloadEstimate
import dev.undine.domain.lfs.LfsGateway
import dev.undine.domain.lfs.LfsLockState
import dev.undine.domain.lfs.LfsObject
import dev.undine.domain.lfs.LfsResult
import dev.undine.domain.lfs.LfsTrackingRule
import dev.undine.domain.lfs.LfsVersion
import dev.undine.infrastructure.git.repository.GitAccess
import java.nio.file.Path

/**
 * [LfsGateway] 의 구현. 두 협력자에 위임하기만 한다 — CLI 조회는 [LfsCliQueries],
 * `.gitattributes` 편집은 [LfsAttributesEditor] 다.
 *
 * **저장소 락 안에서 CLI 를 돌리지 않는다.** [GitAccess] 로는 워킹트리 경로만 짧게 얻고, 프로세스
 * 실행과 파일 편집은 그 밖에서 한다 — `git lfs fetch` 는 분 단위로 걸릴 수 있어 락을 쥔 채 돌면
 * 그동안 모든 Git 접근이 멈춘다. 그래서 협력자에게 경로가 아니라 **경로를 얻는 함수**를 준다.
 *
 * 이 클래스에도 협력자에도 **가변 필드가 없다.** 저장소는 호출 시점에 [gitAccess] 가 답하는 것이
 * 전부이고, 앞선 호출의 판단을 기억하지 않는다.
 */
class LfsGatewayImpl(
    private val gitAccess: GitAccess,
    runner: LfsCommandRunner = ProcessLfsCommandRunner(),
) : LfsGateway {

    private val cli = LfsCliQueries(runner) { workingDirectory() }
    private val attributes = LfsAttributesEditor { workingDirectory() }

    override suspend fun installation(): LfsResult<LfsVersion> = cli.version()

    override suspend fun trackedRules(): List<LfsTrackingRule> = attributes.rules()

    override suspend fun track(pattern: String): List<LfsTrackingRule> = attributes.track(pattern)

    override suspend fun untrack(pattern: String): List<LfsTrackingRule> = attributes.untrack(pattern)

    override suspend fun objects(): LfsResult<List<LfsObject>> = cli.objects()

    override suspend fun pendingDownload(): LfsResult<LfsDownloadEstimate> = cli.pendingDownload()

    override suspend fun download(onProgress: (String) -> Unit): LfsResult<Unit> = cli.download(onProgress)

    override suspend fun locks(): LfsResult<LfsLockState> = cli.locks()

    /** 워킹트리 경로만 락 안에서 짧게 읽는다. bare 저장소는 열리는 경로 자체가 거부한다. */
    private suspend fun workingDirectory(): Path =
        gitAccess.withRepository { repository -> repository.workTree.toPath() }
}
