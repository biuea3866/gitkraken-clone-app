package dev.undine.presentation.preferences

import dev.undine.application.diagnostics.DiagnosticsUseCases
import dev.undine.application.diagnostics.LocateLogDirectoryUseCase
import dev.undine.application.diagnostics.OpenLogDirectoryUseCase
import dev.undine.domain.diagnostics.DiagnosticsGateway
import dev.undine.domain.diagnostics.LogDirectoryLocation
import dev.undine.domain.diagnostics.LogDirectoryMissing
import dev.undine.domain.diagnostics.OpenLogDirectoryResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.nio.file.Path

private const val OPEN_FAILURE_REASON = "파일 관리자를 찾을 수 없습니다"

/**
 * 조회·열기 결과를 정해 두는 Gateway. 실제 파일 관리자 실행은 `DiagnosticsGatewayImplSpec` 이
 * 검증하므로, 여기서는 **화면이 그 결과를 어떻게 다루는지**만 본다.
 *
 * 호출을 [calls] 에 남기는 이유는 부르지 않아야 할 때 부르지 않는가를 보기 위해서다 — 결과 비교로는
 * 없는 디렉터리에 열기를 부르는 회귀가 잡히지 않는다.
 *
 * 열기 결과는 [openResults] 를 **순서대로** 돌려주고 마지막 값을 이후 호출에 반복한다. 같은 홀더에서
 * 실패한 뒤 성공하는 전이를 봐야 사유가 지워지는지 알 수 있다 — 인스턴스를 새로 만들어 비교하면
 * 첫 열기가 성공한 것과 구분되지 않는다.
 */
private class RecordingDiagnosticsGateway(
    private val location: LogDirectoryLocation,
    private val openResults: List<OpenLogDirectoryResult> = listOf(OpenLogDirectoryResult.Opened),
) : DiagnosticsGateway {

    val calls = mutableListOf<String>()

    private var opens: Int = 0

    override suspend fun locateLogDirectory(): LogDirectoryLocation {
        calls += "locate"
        return location
    }

    override suspend fun openLogDirectory(): OpenLogDirectoryResult {
        calls += "open"
        return openResults[minOf(opens++, openResults.lastIndex)]
    }
}

private fun stateOf(gateway: RecordingDiagnosticsGateway): LogDirectoryState = LogDirectoryState(
    scope = CoroutineScope(Dispatchers.Unconfined + Job()),
    diagnostics = DiagnosticsUseCases(
        locateLogDirectory = LocateLogDirectoryUseCase(gateway),
        openLogDirectory = OpenLogDirectoryUseCase(gateway),
    ),
).also(LogDirectoryState::refresh)

/**
 * 고급 탭의 로그 디렉터리 상태 홀더.
 *
 * 보는 것은 경계다: 없는 디렉터리에 열기를 부르지 않는가, 열지 못한 것을 조용한 성공으로 접지
 * 않는가, '아직 없음' 을 실패로 만들지 않는가.
 */
class LogDirectoryStateSpec : FunSpec({

    test("디렉터리가 있으면 경로를 보여 주고 폴더 열기를 내준다") {
        val directory: Path = tempdir().toPath()

        val state = stateOf(RecordingDiagnosticsGateway(LogDirectoryLocation.Found(directory)))

        state.path shouldBe directory.toString()
        state.canOpen shouldBe true
        state.openFailureReason.shouldBeNull()
    }

    test("디렉터리가 아직 없으면 열기를 비활성으로 두고 사유를 띄우지 않는다") {
        val state = stateOf(RecordingDiagnosticsGateway(LogDirectoryMissing))

        state.path.shouldBeNull()
        state.canOpen shouldBe false
        // 아무 문제도 없었다는 뜻이라 사용자가 고칠 것이 없다 — 오류로 알리지 않는다.
        state.openFailureReason.shouldBeNull()
    }

    test("디렉터리가 없으면 열기 요청이 UseCase 에 닿지 않는다") {
        val gateway = RecordingDiagnosticsGateway(LogDirectoryMissing)
        val state = stateOf(gateway)

        state.open()

        gateway.calls shouldContainExactly listOf("locate")
        state.openFailureReason.shouldBeNull()
    }

    test("디렉터리가 있을 때만 열기가 실제로 실행된다") {
        val gateway = RecordingDiagnosticsGateway(LogDirectoryLocation.Found(tempdir().toPath()))
        val state = stateOf(gateway)

        state.open()

        gateway.calls shouldContainExactly listOf("locate", "open")
        state.openFailureReason.shouldBeNull()
    }

    test("파일 관리자를 띄우지 못하면 사유가 그대로 남는다 — 조용한 성공이 되지 않는다") {
        val gateway = RecordingDiagnosticsGateway(
            location = LogDirectoryLocation.Found(tempdir().toPath()),
            openResults = listOf(OpenLogDirectoryResult.OpenFailed(OPEN_FAILURE_REASON)),
        )
        val state = stateOf(gateway)

        state.open()

        state.openFailureReason shouldBe OPEN_FAILURE_REASON
        state.canOpen shouldBe true
    }

    test("조회 뒤 디렉터리가 사라졌으면 표시를 없음으로 맞추고 실패로 알리지 않는다") {
        val gateway = RecordingDiagnosticsGateway(
            location = LogDirectoryLocation.Found(tempdir().toPath()),
            openResults = listOf(LogDirectoryMissing),
        )
        val state = stateOf(gateway)

        state.open()

        state.canOpen shouldBe false
        state.path.shouldBeNull()
        state.openFailureReason.shouldBeNull()
    }

    test("한 번 실패한 뒤 다시 열어 성공하면 사유가 지워진다") {
        val gateway = RecordingDiagnosticsGateway(
            location = LogDirectoryLocation.Found(tempdir().toPath()),
            openResults = listOf(
                OpenLogDirectoryResult.OpenFailed(OPEN_FAILURE_REASON),
                OpenLogDirectoryResult.Opened,
            ),
        )
        val state = stateOf(gateway)
        state.open()
        state.openFailureReason shouldBe OPEN_FAILURE_REASON

        // 같은 홀더에서 다시 연다 — 지난 사유가 남아 있으면 사용자는 방금 성공한 열기를 실패로 본다.
        state.open()

        state.openFailureReason.shouldBeNull()
        gateway.calls shouldContainExactly listOf("locate", "open", "open")
    }

    test("열기에 성공한 뒤 다시 실패하면 새 사유가 올라온다") {
        val gateway = RecordingDiagnosticsGateway(
            location = LogDirectoryLocation.Found(tempdir().toPath()),
            openResults = listOf(
                OpenLogDirectoryResult.Opened,
                OpenLogDirectoryResult.OpenFailed(OPEN_FAILURE_REASON),
            ),
        )
        val state = stateOf(gateway)
        state.open()
        state.openFailureReason.shouldBeNull()

        state.open()

        state.openFailureReason shouldBe OPEN_FAILURE_REASON
    }

    test("조회 전에는 없음으로 열려 있어 열기 버튼이 눌리지 않는다") {
        val gateway = RecordingDiagnosticsGateway(LogDirectoryLocation.Found(tempdir().toPath()))
        val state = LogDirectoryState(
            scope = CoroutineScope(Dispatchers.Unconfined + Job()),
            diagnostics = DiagnosticsUseCases(
                locateLogDirectory = LocateLogDirectoryUseCase(gateway),
                openLogDirectory = OpenLogDirectoryUseCase(gateway),
            ),
        )

        state.canOpen shouldBe false
        gateway.calls.shouldBeEmpty()
    }
})
