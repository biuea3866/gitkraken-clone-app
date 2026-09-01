package dev.undine.infrastructure.diagnostics

import dev.undine.domain.diagnostics.DiagnosticsGateway
import dev.undine.domain.diagnostics.FileManagerLauncher
import dev.undine.domain.diagnostics.LogDirectoryLocation
import dev.undine.domain.diagnostics.LogDirectoryMissing
import dev.undine.domain.diagnostics.OpenLogDirectoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * [DiagnosticsGateway] 의 구현.
 *
 * [appDirectory] 를 **생성자로 받는다** — 경로 계산은 `App.kt` 의 한 곳에만 두고 이 구현은 받은 값을
 * 그대로 쓴다 (wave 8 결정 G35). 같은 값이 `crash.log` 기록 경로에도 쓰이므로 지식이 복제되지 않는다.
 *
 * 파일 관리자 실행은 [FileManagerLauncher] 경계 뒤로 밀어 테스트가 데스크톱 환경에 의존하지 않게 한다.
 */
class DiagnosticsGatewayImpl(
    private val appDirectory: Path,
    private val fileManagerLauncher: FileManagerLauncher = DesktopFileManagerLauncher(),
) : DiagnosticsGateway {

    override suspend fun locateLogDirectory(): LogDirectoryLocation = withContext(Dispatchers.IO) {
        if (Files.isDirectory(appDirectory)) LogDirectoryLocation.Found(appDirectory) else LogDirectoryMissing
    }

    override suspend fun openLogDirectory(): OpenLogDirectoryResult {
        val directory = when (val location = locateLogDirectory()) {
            is LogDirectoryMissing -> return LogDirectoryMissing
            is LogDirectoryLocation.Found -> location.path
        }
        return try {
            fileManagerLauncher.open(directory)
            OpenLogDirectoryResult.Opened
        } catch (failure: IOException) {
            // 실패를 결과로 바꾸되 사유는 버리지 않는다 — 화면이 보여 줄 문구가 이 값 하나뿐이다.
            System.err.println(
                "[undine] diagnostics.open-log-directory-failed type=${failure::class.simpleName}",
            )
            OpenLogDirectoryResult.OpenFailed(failure.message ?: FALLBACK_FAILURE_REASON)
        }
    }
}

private const val FALLBACK_FAILURE_REASON = "파일 관리자를 열지 못했습니다"
