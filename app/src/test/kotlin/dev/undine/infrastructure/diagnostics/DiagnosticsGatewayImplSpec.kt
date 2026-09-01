package dev.undine.infrastructure.diagnostics

import dev.undine.domain.diagnostics.FileManagerLauncher
import dev.undine.domain.diagnostics.LogDirectoryLocation
import dev.undine.domain.diagnostics.LogDirectoryMissing
import dev.undine.domain.diagnostics.OpenLogDirectoryResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private const val LAUNCH_FAILURE_REASON = "파일 관리자를 띄우지 못했습니다"

/**
 * 파일 관리자 실행만 가짜 [FileManagerLauncher] 로 대체하고 경로 판정은 **실제 임시 디렉터리**로
 * 검증한다 — 개발·CI 에 데스크톱 환경이 없어도 '있음/아직 없음/열기 실패' 를 결정적으로 가른다.
 */
class DiagnosticsGatewayImplSpec : FunSpec({

    test("앱 디렉터리가 있으면 그 경로를 조회 결과로 돌려준다") {
        val appDirectory = tempdir().toPath()

        DiagnosticsGatewayImpl(appDirectory, RecordingLauncher()).locateLogDirectory() shouldBe
            LogDirectoryLocation.Found(appDirectory)
    }

    test("앱 디렉터리가 없으면 실패가 아니라 '아직 없음' 이고 경로를 만들지 않는다") {
        val appDirectory = tempdir().toPath().resolve("아직-없는-디렉터리")

        DiagnosticsGatewayImpl(appDirectory, RecordingLauncher()).locateLogDirectory() shouldBe LogDirectoryMissing
        Files.exists(appDirectory) shouldBe false
    }

    test("앱 디렉터리 자리에 파일이 있으면 디렉터리가 아니므로 '아직 없음' 이다") {
        val appDirectory = tempdir().toPath().resolve("undine")
        Files.writeString(appDirectory, "디렉터리가 아니다")

        DiagnosticsGatewayImpl(appDirectory, RecordingLauncher()).locateLogDirectory() shouldBe LogDirectoryMissing
    }

    test("열기는 조회한 앱 디렉터리와 정확히 같은 경로로 파일 관리자를 부른다") {
        val appDirectory = tempdir().toPath()
        val launcher = RecordingLauncher()

        DiagnosticsGatewayImpl(appDirectory, launcher).openLogDirectory() shouldBe OpenLogDirectoryResult.Opened

        launcher.opened shouldContainExactly listOf(appDirectory)
    }

    test("앱 디렉터리가 없으면 파일 관리자를 부르지 않고 '아직 없음' 을 돌려준다") {
        val appDirectory = tempdir().toPath().resolve("아직-없는-디렉터리")
        val launcher = RecordingLauncher()

        DiagnosticsGatewayImpl(appDirectory, launcher).openLogDirectory() shouldBe LogDirectoryMissing

        launcher.opened.shouldBeEmpty()
    }

    test("파일 관리자가 실패하면 사유를 담아 돌려주고 조용한 성공으로 바꾸지 않는다") {
        val appDirectory = tempdir().toPath()
        val launcher = RecordingLauncher(failure = { IOException(LAUNCH_FAILURE_REASON) })

        val result = DiagnosticsGatewayImpl(appDirectory, launcher).openLogDirectory()

        result shouldBe OpenLogDirectoryResult.OpenFailed(LAUNCH_FAILURE_REASON)
    }

    test("조회 뒤 디렉터리가 사라져 실행이 실패해도 실패를 숨기지 않는다") {
        val appDirectory = tempdir().toPath().resolve("사라질-디렉터리")
        Files.createDirectory(appDirectory)
        // 판정과 실행 사이에 대상이 사라지는 경로 — 열기 실패는 '아직 없음' 이 아니라 실패로 남는다.
        val launcher = RecordingLauncher(
            failure = { directory ->
                Files.deleteIfExists(directory)
                NoSuchFileException(directory.toString())
            },
        )

        val result = DiagnosticsGatewayImpl(appDirectory, launcher).openLogDirectory()

        result shouldBe OpenLogDirectoryResult.OpenFailed(appDirectory.toString())
        Files.exists(appDirectory) shouldBe false
    }

    test("열기 도중 취소되면 실패 결과로 접지 않고 취소를 전파한다") {
        val appDirectory = tempdir().toPath()
        val launcher = RecordingLauncher(failure = { CancellationException("취소") })

        shouldThrow<CancellationException> {
            DiagnosticsGatewayImpl(appDirectory, launcher).openLogDirectory()
        }
    }
})

private class RecordingLauncher(
    private val failure: ((Path) -> Throwable)? = null,
) : FileManagerLauncher {

    val opened = mutableListOf<Path>()

    override suspend fun open(directory: Path) {
        failure?.let { build -> throw build(directory) }
        opened.add(directory)
    }
}
