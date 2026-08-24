package dev.undine.presentation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime

/**
 * 어디서도 잡히지 않은 예외를 화면으로 끌어올린다.
 *
 * 처리되지 않은 예외로 앱이 조용히 죽으면 사용자는 원인을 알 수 없다 — 크래시 대신 안내를 띄우고
 * 로그 위치를 알린다. 예외 **원문을 화면에 그대로 내지 않는다**: 원격 관련 예외 메시지에는
 * URL·자격증명 구간이 섞일 수 있어(`credential-handling` 2항) 화면에는 종류와 로그 경로만 보인다.
 */
@Stable
class AppErrorState {

    /** 지금 안내할 실패. `null` 이면 없다. */
    var failure: AppFailure? by mutableStateOf(null)
        private set

    fun report(throwable: Throwable, logPath: Path?) {
        failure = AppFailure(kind = throwable::class.simpleName ?: "Throwable", logPath = logPath)
    }

    fun dismiss() {
        failure = null
    }
}

/** 화면에 낼 수 있는 만큼만 담은 실패 정보. 예외 메시지는 담지 않는다. */
data class AppFailure(val kind: String, val logPath: Path?)

/**
 * 전역 예외 처리기를 설치한다. `main` 에서 창을 열기 전에 한 번 부른다.
 *
 * 스택트레이스는 [logDirectory] 아래 파일로 남긴다 — 화면에 낼 수 없는 정보라도 원인 추적에는
 * 필요하다. 기존 처리기가 있으면 뒤이어 호출한다(우리가 마지막 처리기라고 가정하지 않는다).
 */
fun installGlobalExceptionHandler(errors: AppErrorState, logDirectory: Path) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val logPath = writeCrashLog(logDirectory, thread.name, throwable)
        errors.report(throwable, logPath)
        previous?.uncaughtException(thread, throwable)
    }
}

/**
 * 스택트레이스를 파일로 남기고 그 경로를 돌려준다. 남기지 못하면 `null` —
 * 로그를 못 쓰는 상황(권한·디스크)에서 그 실패로 처리기가 다시 던지면 원 예외가 묻힌다.
 */
private fun writeCrashLog(logDirectory: Path, threadName: String, throwable: Throwable): Path? =
    runCatching {
        Files.createDirectories(logDirectory)
        val path = logDirectory.resolve("crash.log")
        PrintWriter(Files.newBufferedWriter(path)).use { writer ->
            writer.println("[${ZonedDateTime.now()}] thread=$threadName")
            throwable.printStackTrace(writer)
        }
        path
    }.getOrNull()
