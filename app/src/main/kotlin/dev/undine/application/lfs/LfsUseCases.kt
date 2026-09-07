package dev.undine.application.lfs

import dev.undine.domain.lfs.LfsDownloadEstimate
import dev.undine.domain.lfs.LfsGateway
import dev.undine.domain.lfs.LfsLockState
import dev.undine.domain.lfs.LfsObject
import dev.undine.domain.lfs.LfsResult
import dev.undine.domain.lfs.LfsTrackingRule
import dev.undine.domain.lfs.LfsVersion

/**
 * `git-lfs` 설치 여부와 버전. 미설치·시작 실패는 결과 값으로 구분돼 올라온다 — 판단은 화면이 한다.
 */
class LoadLfsInstallationUseCase(private val lfsGateway: LfsGateway) {

    suspend fun execute(): LfsResult<LfsVersion> = lfsGateway.installation()
}

/** `.gitattributes` 의 LFS 추적 규칙 조회. `git-lfs` 가 없어도 답할 수 있다. */
class LoadLfsTrackingRulesUseCase(private val lfsGateway: LfsGateway) {

    suspend fun execute(): List<LfsTrackingRule> = lfsGateway.trackedRules()
}

/** 추적 규칙 추가. 갱신된 규칙 목록을 그대로 올린다. */
class TrackLfsPatternUseCase(private val lfsGateway: LfsGateway) {

    suspend fun execute(pattern: String): List<LfsTrackingRule> = lfsGateway.track(pattern)
}

/** 추적 규칙 제거. 규칙이 0건이 되어도 `.gitattributes` 는 0바이트로 남는다. */
class UntrackLfsPatternUseCase(private val lfsGateway: LfsGateway) {

    suspend fun execute(pattern: String): List<LfsTrackingRule> = lfsGateway.untrack(pattern)
}

/** 워킹트리 LFS 객체 상태. 포인터만 있는 것과 내려받은 것이 구분돼 올라온다. */
class LoadLfsObjectsUseCase(private val lfsGateway: LfsGateway) {

    suspend fun execute(): LfsResult<List<LfsObject>> = lfsGateway.objects()
}

/**
 * 받을 객체와 합계 바이트 조회. **아무것도 내려받지 않는다.**
 *
 * 실제 수신은 [DownloadLfsObjectsUseCase] 를 호출자가 **따로** 부를 때만 일어난다 — 사용자가
 * 거절하면 그 호출을 하지 않으므로, 거절은 결과 값이 아니라 호출의 부재로 표현된다.
 */
class EstimateLfsDownloadUseCase(private val lfsGateway: LfsGateway) {

    suspend fun execute(): LfsResult<LfsDownloadEstimate> = lfsGateway.pendingDownload()
}

/**
 * 객체 다운로드. 진행 상황은 [onProgress] 로 한 줄씩 나가고, 중단은 호출자의 코루틴 취소다.
 *
 * 크기 확인은 여기서 하지 않는다 — 확인과 수신을 한 호출에 묶으면 "사용자가 거절함" 이 Gateway 의
 * 실패 값으로 섞여 들어온다.
 */
class DownloadLfsObjectsUseCase(private val lfsGateway: LfsGateway) {

    suspend fun execute(onProgress: (String) -> Unit): LfsResult<Unit> = lfsGateway.download(onProgress)
}

/** 서버 잠금 지원 여부와 현재 잠금 목록. 생성·해제는 제공하지 않는다. */
class LoadLfsLocksUseCase(private val lfsGateway: LfsGateway) {

    suspend fun execute(): LfsResult<LfsLockState> = lfsGateway.locks()
}

/**
 * LFS UseCase 묶음. 묶는 이유는 [dev.undine.application.externaltool.ExternalToolUseCases] 와 같다 —
 * 동작이 늘어도 호출부의 시그니처가 바뀌지 않아야 한다.
 *
 * 이 티켓은 **화면을 만들지 않는다.** diff 의 LFS 표시 외의 기능(추적 규칙 관리·잠금·다운로드)은
 * 여기까지가 범위이고, 화면은 요구되지 않았다.
 */
data class LfsUseCases(
    val installation: LoadLfsInstallationUseCase,
    val trackingRules: LoadLfsTrackingRulesUseCase,
    val track: TrackLfsPatternUseCase,
    val untrack: UntrackLfsPatternUseCase,
    val objects: LoadLfsObjectsUseCase,
    val estimateDownload: EstimateLfsDownloadUseCase,
    val download: DownloadLfsObjectsUseCase,
    val locks: LoadLfsLocksUseCase,
)
