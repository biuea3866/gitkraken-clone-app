package dev.undine.application.lfs

import dev.undine.domain.lfs.LfsDownloadEstimate
import dev.undine.domain.lfs.LfsGateway
import dev.undine.domain.lfs.LfsLock
import dev.undine.domain.lfs.LfsLockState
import dev.undine.domain.lfs.LfsObject
import dev.undine.domain.lfs.LfsObjectState
import dev.undine.domain.lfs.LfsResult
import dev.undine.domain.lfs.LfsTrackingRule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot

private const val PATTERN = "*.psd"

private val PENDING = LfsObject("bbb", "pointer.psd", LfsObjectState.POINTER_ONLY)

private const val FAILURE_EXIT_CODE = 2

/**
 * CLI 조회가 실패할 수 있는 네 갈래 전부. 넷 다 `LfsResult<Nothing>` 이라 어느 조회에도 그대로 쓴다.
 */
private val FAILURES: List<LfsResult<Nothing>> = listOf(
    LfsResult.NotInstalled,
    LfsResult.StartFailed("실행 권한이 없습니다"),
    LfsResult.CommandFailed(FAILURE_EXIT_CODE, "무언가 잘못됨"),
    LfsResult.OutputUnrecognized("무슨 말인지 모를 출력"),
)

/**
 * UseCase 는 판단하지 않고 Gateway 결과를 그대로 올린다. 여기서 보는 것은 **호출 구조**다 —
 * 크기 조회와 다운로드가 두 호출로 갈라져 있어야 "사용자가 거절함" 이 결과 값으로 섞이지 않는다.
 */
class LfsUseCasesSpec : BehaviorSpec({

    given("LFS Gateway") {

        `when`("다운로드 크기를 조회하면") {
            then("받을 객체와 합계만 올라오고 다운로드는 호출되지 않는다") {
                val gateway = mockk<LfsGateway>()
                val estimate = LfsDownloadEstimate(objects = listOf(PENDING), totalBytes = 2_000L)
                coEvery { gateway.pendingDownload() } returns LfsResult.Available(estimate)

                EstimateLfsDownloadUseCase(gateway).execute() shouldBe LfsResult.Available(estimate)

                coVerify(exactly = 0) { gateway.download(any()) }
            }
        }

        `when`("사용자가 확인한 뒤 다운로드를 호출하면") {
            then("진행 상황 콜백이 그대로 위임된다") {
                val gateway = mockk<LfsGateway>()
                val forwarded = slot<(String) -> Unit>()
                coEvery { gateway.download(capture(forwarded)) } returns LfsResult.Available(Unit)
                val progress = mutableListOf<String>()

                DownloadLfsObjectsUseCase(gateway).execute { line -> progress += line }
                forwarded.captured("받는 중 1/1")

                progress shouldContainExactly listOf("받는 중 1/1")
            }
        }

        `when`("사용자가 다운로드를 거절하면") {
            then("다운로드 UseCase 를 부르지 않으므로 Gateway 에 아무것도 도달하지 않는다") {
                val gateway = mockk<LfsGateway>()
                coEvery { gateway.pendingDownload() } returns
                    LfsResult.Available(LfsDownloadEstimate(objects = listOf(PENDING), totalBytes = 2_000L))

                EstimateLfsDownloadUseCase(gateway).execute()
                // 거절은 호출의 부재다 — Gateway 계약에 승인 콜백을 두지 않는 이유다.

                coVerify(exactly = 0) { gateway.download(any()) }
            }
        }

        `when`("Gateway 가 미설치·시작 실패를 올리면") {
            then("UseCase 가 그 값을 접지 않고 그대로 전파한다") {
                val gateway = mockk<LfsGateway>()
                coEvery { gateway.installation() } returns LfsResult.NotInstalled
                coEvery { gateway.objects() } returns LfsResult.StartFailed("권한 거부")

                LoadLfsInstallationUseCase(gateway).execute() shouldBe LfsResult.NotInstalled
                LoadLfsObjectsUseCase(gateway).execute() shouldBe LfsResult.StartFailed("권한 거부")
            }
        }

        // 실패를 성공처럼 접으면 화면이 "받을 것 0건" 이라고 거짓말한다 — 네 실패 모두 그대로 올라와야 한다.
        FAILURES.forEach { failure ->
            `when`("크기 조회가 ${failure::class.simpleName} 로 실패하면") {
                then("EstimateLfsDownloadUseCase 가 그 값을 그대로 올린다") {
                    val gateway = mockk<LfsGateway>()
                    coEvery { gateway.pendingDownload() } returns failure

                    EstimateLfsDownloadUseCase(gateway).execute() shouldBe failure
                }
            }

            `when`("다운로드가 ${failure::class.simpleName} 로 실패하면") {
                then("DownloadLfsObjectsUseCase 가 그 값을 그대로 올린다") {
                    val gateway = mockk<LfsGateway>()
                    coEvery { gateway.download(any()) } returns failure

                    DownloadLfsObjectsUseCase(gateway).execute { } shouldBe failure
                }
            }

            `when`("잠금 조회가 ${failure::class.simpleName} 로 실패하면") {
                then("LoadLfsLocksUseCase 가 그 값을 그대로 올린다") {
                    val gateway = mockk<LfsGateway>()
                    coEvery { gateway.locks() } returns failure

                    LoadLfsLocksUseCase(gateway).execute() shouldBe failure
                }
            }
        }

        `when`("잠금 상태가 활성·비활성으로 올라오면") {
            then("LoadLfsLocksUseCase 가 두 상태를 구분해 그대로 전달한다") {
                val gateway = mockk<LfsGateway>()
                val active = LfsResult.Available(LfsLockState.Active(listOf(LfsLock("a.psd", "alice"))))
                coEvery { gateway.locks() } returns active
                LoadLfsLocksUseCase(gateway).execute() shouldBe active

                // 비활성은 실패가 아니다 — 사유를 실은 정상 값이라 접히면 안 된다.
                val inactive = LfsResult.Available(LfsLockState.Inactive("지원하지 않는 서버"))
                coEvery { gateway.locks() } returns inactive
                LoadLfsLocksUseCase(gateway).execute() shouldBe inactive
            }
        }

        `when`("추적 규칙을 더하고 빼면") {
            then("갱신된 규칙 목록이 그대로 올라온다") {
                val gateway = mockk<LfsGateway>()
                coEvery { gateway.track(PATTERN) } returns listOf(LfsTrackingRule(PATTERN))
                coEvery { gateway.untrack(PATTERN) } returns emptyList()
                coEvery { gateway.trackedRules() } returns emptyList()

                TrackLfsPatternUseCase(gateway).execute(PATTERN) shouldContainExactly
                    listOf(LfsTrackingRule(PATTERN))
                UntrackLfsPatternUseCase(gateway).execute(PATTERN) shouldBe emptyList()
                LoadLfsTrackingRulesUseCase(gateway).execute() shouldBe emptyList()
            }
        }
    }
})
