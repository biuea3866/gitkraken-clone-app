package dev.undine.infrastructure.git.lfs

import dev.undine.domain.lfs.LfsCommandOutcome
import dev.undine.domain.lfs.LfsCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 기본 실행 파일. `git` 바이너리를 거치지 않는다 — 이 앱의 Git 접근은 JGit 이라 `git` 이 없을 수 있다. */
internal const val DEFAULT_LFS_EXECUTABLE = "git-lfs"

/**
 * `git-lfs` 하위 명령 인자를 **실제 실행 명령**으로 만드는 경계. 첫 원소가 실행 파일이다.
 *
 * 이름 해소를 여기에 가두는 이유는 테스트가 전역 `PATH` 를 건드리지 않게 하기 위해서다 —
 * 대역은 자기 명령을 만들어 주입하고, 프로덕션 기본값만 `PATH` 에서 `git-lfs` 를 찾는다.
 */
internal fun interface LfsCommandLine {

    fun commandFor(arguments: List<String>): List<String>

    companion object {

        val Default = LfsCommandLine { arguments -> listOf(DEFAULT_LFS_EXECUTABLE) + arguments }
    }
}

/**
 * 실제 `git-lfs` 프로세스 경계. 명령을 셸에 넘기지 않고 [ProcessBuilder] 에 인자 배열로 전달한다.
 *
 * 세 가지가 이 클래스의 계약이다.
 * 1. **미설치와 시작 실패를 구분한다.** 모든 `IOException` 을 미설치로 뭉개면 사용자는 설치하면
 *    되는 문제와 환경이 잘못된 문제를 구분하지 못한다. 이름 해소를 먼저 해서 가른다.
 * 2. **기다리기 전에 두 스트림을 비운다.** 아무도 읽지 않는 파이프가 차면 자식이 쓰기에서 멈춰
 *    `waitFor` 가 영영 돌아오지 않는다. 비우기는 선언한 `Dispatchers.IO` 경계 안에서 한다.
 * 3. **취소는 핸들 획득부터 자손 종료까지 덮는다.** 종료를 요청만 하고 돌아가면 살아남은 자식이
 *    워킹트리를 계속 잡는다. stdin 을 포함한 세 스트림은 모든 종료 경로에서 닫는다.
 */
class ProcessLfsCommandRunner internal constructor(
    private val commandLine: LfsCommandLine,
) : LfsCommandRunner {

    constructor() : this(LfsCommandLine.Default)

    override suspend fun run(
        arguments: List<String>,
        workingDirectory: Path,
        onOutputLine: (String) -> Unit,
    ): LfsCommandOutcome {
        // 시작과 대기를 한 범위에 묶고 핸들을 그 **안에서** 밖으로 넘긴다. 시작을 따로 떼면
        // 그 사이 취소된 호출자로 돌아올 때 결과가 버려져 프로세스만 남는다.
        var started: Process? = null
        var completed = false
        return try {
            withContext(Dispatchers.IO) {
                // 이름 해소는 PATH 를 뒤지는 **파일시스템 작업**이다. 프로세스 실행만 IO 로 보고
                // 밖에 두면 UI 코루틴에서 부를 때 디스크 조회가 UI 스레드에서 돈다.
                val command = commandLine.commandFor(arguments)
                require(command.isNotEmpty()) { "git-lfs 명령은 비어 있을 수 없습니다" }
                executableFailure(command.first())?.let { failure -> return@withContext failure }

                val process = try {
                    ProcessBuilder(command).directory(workingDirectory.toFile()).start()
                } catch (failure: IOException) {
                    return@withContext startFailed(failure)
                }
                started = process
                // 열린 채로 두면 입력을 읽는 도구가 EOF 를 못 봐 대기한다. 넘길 입력이 없으니 곧바로 닫는다.
                runCatching { process.outputStream.close() }
                collect(process, onOutputLine)
            }.also { completed = true }
        } finally {
            // **정상 완료가 아니면 프로세스가 아직 살아 있다.** 취소만이 아니라 진행률 콜백이 던진
            // 예외·drain 실패도 여기로 온다 — 취소 경로만 덮으면 호출은 실패했는데 git-lfs 는
            // 계속 돌고, 살아남은 자식이 워킹트리를 잡는다. 종료를 요청만 하지 않고 확인하고 나간다.
            started?.let { process ->
                if (!completed) withContext(NonCancellable + Dispatchers.IO) { awaitTermination(process) }
                // 어느 경로로 나가든 이 호출이 연 스트림은 남기지 않는다.
                closeStreams(process)
            }
        }
    }

    /** 두 스트림을 **동시에** 비우고 그 뒤에 종료를 기다린다. 순서를 바꾸면 파이프가 차서 멈춘다. */
    private suspend fun collect(process: Process, onOutputLine: (String) -> Unit): LfsCommandOutcome =
        coroutineScope {
            val standardOutput = async { drain(process.inputStream, onOutputLine) }
            val standardError = async { drain(process.errorStream) { } }
            val output = standardOutput.await()
            val error = standardError.await()
            LfsCommandOutcome.Completed(
                exitCode = runInterruptible { process.waitFor() },
                standardOutput = output,
                standardError = error,
            )
        }

    private suspend fun drain(stream: InputStream, onLine: (String) -> Unit): String = runInterruptible {
        val collected = StringBuilder()
        stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.lineSequence().forEach { line ->
                collected.append(line).append('\n')
                onLine(line)
            }
        }
        collected.toString()
    }

    /**
     * 실행 파일 이름을 해소해 **미설치**와 **시작 실패**를 가른다. 실행 가능하면 `null` 이다.
     *
     * 경로 문자가 든 이름은 그 자리에서, 아니면 `PATH` 에서 찾는다. 찾았는데 실행 권한이 없으면
     * 미설치가 아니라 시작 실패다 — 사용자가 할 일이 다르다.
     */
    @Suppress("ReturnCount")
    private fun executableFailure(executable: String): LfsCommandOutcome? {
        val candidates = candidatePathsFor(executable)
        val found = candidates.firstOrNull { path -> Files.isRegularFile(path) }
            ?: return LfsCommandOutcome.NotInstalled
        if (!Files.isExecutable(found)) return LfsCommandOutcome.StartFailed("실행 권한이 없습니다: $found")
        return null
    }

    private fun candidatePathsFor(executable: String): List<Path> {
        val candidate = Path.of(executable)
        if (candidate.isAbsolute || executable.contains(File.separatorChar)) return listOf(candidate)
        return System.getenv("PATH")
            ?.split(File.pathSeparator)
            .orEmpty()
            .filter { directory -> directory.isNotEmpty() }
            .map { directory -> Path.of(directory, executable) }
    }

    private fun startFailed(failure: IOException): LfsCommandOutcome.StartFailed =
        LfsCommandOutcome.StartFailed("${failure::class.simpleName}: ${failure.message.orEmpty()}")

    /**
     * 종료 요청과 종료 확인의 순서가 이 계약의 핵심이다.
     *
     * 루트만 죽이면 자식이 살아남아 워킹트리 파일을 계속 잡는다. 자손 목록은 **죽이기 전에**
     * 스냅샷으로 떠 두고, 부모와 자손 모두가 실제로 끝난 것을 확인한 뒤에 돌아간다.
     */
    private fun awaitTermination(process: Process) {
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
        // 종료를 관측할 수 없는 핸들은 이미 사라진 것이라 '살아 있지 않음' 과 같다.
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
