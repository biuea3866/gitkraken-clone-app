package dev.undine.infrastructure.externaltool

import dev.undine.domain.externaltool.ExternalToolRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** 실제 외부 도구 프로세스 경계. 명령을 셸에 넘기지 않고 [ProcessBuilder] 에 인자 배열로 전달한다. */
class ProcessExternalToolRunner : ExternalToolRunner {

    override suspend fun isInstalled(executable: String): Boolean = withContext(Dispatchers.IO) {
        if (executable.isBlank()) return@withContext false
        val candidate = Path.of(executable)
        if (candidate.isAbsolute || executable.contains(File.separatorChar)) {
            return@withContext Files.isRegularFile(candidate) && Files.isExecutable(candidate)
        }
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            .orEmpty()
            .asSequence()
            .map { directory -> Path.of(directory, executable) }
            .any { path -> Files.isRegularFile(path) && Files.isExecutable(path) }
    }

    override suspend fun run(command: List<String>): Int {
        require(command.isNotEmpty()) { "외부 도구 명령은 비어 있을 수 없습니다" }
        // 시작과 대기를 한 범위에 묶고 핸들을 그 **안에서** 밖으로 넘긴다. 시작을 별도 withContext 로
        // 떼어 두면, 그 사이에 취소된 호출자로 돌아올 때 결과가 버려져 프로세스만 남는다.
        var started: Process? = null
        return try {
            withContext(Dispatchers.IO) {
                val process = ProcessBuilder(command)
                    // 파이프로 두면 아무도 읽지 않는 버퍼가 차는 순간 도구가 쓰기에서 멈춰 영영 끝나지
                    // 않는다. 도구 출력은 화면에 쓰지 않으므로 읽는 대신 버린다.
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                started = process
                // 열린 채로 두면 입력을 읽는 도구가 EOF 를 못 봐 대기한다. 넘길 입력이 없으니 곧바로 닫는다.
                runCatching { process.outputStream.close() }
                runInterruptible { process.waitFor() }
            }
        } catch (cancellation: CancellationException) {
            // 종료를 요청만 하고 돌아가면 호출자(Gateway)의 임시 파일 정리가 아직 살아 있는 프로세스와
            // 경합한다. 실제 종료를 확인한 뒤에야 취소를 전파한다.
            started?.let { process -> withContext(NonCancellable + Dispatchers.IO) { awaitTermination(process) } }
            throw cancellation
        } finally {
            // 정상·실패·취소 어느 경로로 나가든 이 호출이 연 스트림은 남기지 않는다.
            started?.let { process -> closeStreams(process) }
        }
    }

    /**
     * 종료 요청과 종료 확인의 순서가 이 계약의 핵심이라 테스트가 직접 이 경계를 검증한다.
     *
     * 루트만 죽이면 도구가 띄운 자식이 살아남아 임시 파일을 계속 잡는다. 자손 목록은 **죽이기 전에**
     * 스냅샷으로 떠 두고, 부모와 자손 모두가 실제로 끝난 것을 확인한 뒤에 돌아간다.
     */
    internal fun awaitTermination(process: Process) {
        // 죽인 뒤에는 자손이 열거되지 않으므로 목록을 먼저 뜬다.
        val descendants = process.descendants().toList()
        process.destroyForcibly()
        descendants.forEach { descendant -> descendant.destroyForcibly() }
        closeStreams(process)
        awaitExit(process)
        descendants.forEach { descendant -> awaitExit(descendant) }
    }

    /** 자식이 읽지도 쓰지도 않는 파이프에 막혀 종료가 지연되지 않도록 세 스트림을 모두 닫는다. */
    private fun closeStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun awaitExit(process: Process) {
        while (true) {
            try {
                process.waitFor()
                return
            } catch (interrupted: InterruptedException) {
                // runInterruptible 이 남긴 인터럽트가 대기를 곧바로 깨울 수 있다. 종료를 확인할 때까지
                // 다시 기다리되 풀 스레드에 인터럽트 상태를 남기지 않는다.
                Thread.interrupted()
            }
        }
    }

    private fun awaitExit(descendant: ProcessHandle) {
        // 종료를 관측할 수 없는 핸들은 이미 사라진 것이라 '살아 있지 않음' 과 같다. 그 실패로 취소 전파를
        // 대신 막지 않도록 여기서 정상 종료로 접는다.
        val termination = descendant.onExit().handle { _, _ -> Unit }
        while (true) {
            try {
                termination.get()
                return
            } catch (interrupted: InterruptedException) {
                Thread.interrupted()
            }
        }
    }
}
