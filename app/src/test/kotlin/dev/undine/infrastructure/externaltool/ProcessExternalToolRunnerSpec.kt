package dev.undine.infrastructure.externaltool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

private const val FAILURE_EXIT_CODE = 7
private const val PROCESS_START_WAIT_MILLIS = 10_000L
private const val POLL_INTERVAL_MILLIS = 20L

/** 내부 시간 제한이 있었다면 이 창 안에서 실행이 스스로 끝난다. 대화형 도구의 정상 대기보다는 짧게 잡는다. */
private const val NO_TIME_LIMIT_OBSERVATION_MILLIS = 700L

private const val START_CANCEL_ATTEMPTS = 24
private const val START_CANCEL_JITTER_MILLIS = 4L

/** 파이프 버퍼(대개 64KB)를 훌쩍 넘겨야 '읽지 않으면 막힌다' 를 재현할 수 있다. */
private const val FLOOD_OUTPUT_BYTES = 4 * 1024 * 1024

/** 막히지 않는다면 이 안에 끝난다. 넘기면 실패로 드러나야지 테스트가 매달려서는 안 된다. */
private const val FLOOD_OUTPUT_TIMEOUT_MILLIS = 60_000L

/** 새어 나간 프로세스는 JVM 부팅을 마쳐야 pid 를 적는다. 그 시간을 준 뒤에 확인한다. */
private const val LEAK_SETTLE_MILLIS = 1_500L

/**
 * 실제 프로세스 경계 자체를 검증한다. 시스템에 깔린 diff/merge 도구에 기대지 않고 테스트가
 * **자기 JVM 을 도구 대역으로** 다시 띄운다 ([ExternalToolTestProcess]) — 셸 스크립트 픽스처와 달리
 * POSIX 실행 권한이 없는 파일 시스템에서도 그대로 돌아 지원 플랫폼 전부를 덮는다.
 */
class ProcessExternalToolRunnerSpec : FunSpec({

    val runner = ProcessExternalToolRunner()

    test("PATH 에 있는 이름은 설치된 것으로 판정한다") {
        val executableOnPath = firstExecutableNameOnPath()

        executableOnPath.shouldNotBeNull()
        runner.isInstalled(executableOnPath) shouldBe true
    }

    test("PATH 어디에도 없는 이름과 빈 이름은 설치되지 않은 것으로 판정한다") {
        runner.isInstalled("undine-absent-external-tool") shouldBe false
        runner.isInstalled("") shouldBe false
    }

    test("절대 경로는 실행 권한이 있는 일반 파일일 때만 설치로 판정한다") {
        val directory = tempdir()
        val plainFile = Files.writeString(directory.toPath().resolve("plain.txt"), "not executable")

        runner.isInstalled(ExternalToolTestProcess.javaExecutable()) shouldBe true
        runner.isInstalled(plainFile.toString()) shouldBe false
        runner.isInstalled(directory.toPath().toString()) shouldBe false
    }

    test("공백·특수문자가 든 인자를 쪼개지 않고 인자 하나씩 그대로 전달한다") {
        val output = tempdir().toPath().resolve("arguments.txt")
        val arguments = listOf(
            "with space",
            "semi;colon && ampersand",
            "dollar \$HOME and 'quote'",
        )

        val command = ExternalToolTestProcess.command(
            ExternalToolTestProcess.ECHO_ARGUMENTS,
            output.toString(),
            *arguments.toTypedArray(),
        )
        runner.run(command) shouldBe 0

        Files.readAllLines(output) shouldContainExactly arguments
    }

    test("도구의 비정상 종료 코드를 그대로 돌려준다") {
        val command = ExternalToolTestProcess.command(
            ExternalToolTestProcess.EXIT_WITH,
            FAILURE_EXIT_CODE.toString(),
        )

        runner.run(command) shouldBe FAILURE_EXIT_CODE
    }

    test("출력을 대량으로 쏟는 도구도 파이프에 막히지 않고 끝까지 실행된다") {
        val command = ExternalToolTestProcess.command(
            ExternalToolTestProcess.FLOOD_OUTPUT,
            FLOOD_OUTPUT_BYTES.toString(),
        )

        // 아무도 읽지 않는 파이프로 두면 도구가 쓰기에서 멈춰 waitFor 가 영영 돌아오지 않는다.
        withTimeout(FLOOD_OUTPUT_TIMEOUT_MILLIS) { runner.run(command) shouldBe 0 }
    }

    test("빈 명령은 사전조건 위반으로 거부한다") {
        shouldThrow<IllegalArgumentException> { runner.run(emptyList()) }
    }

    test("취소 정리는 종료를 요청한 뒤 실제 종료를 확인한다") {
        val process = RecordingProcess()

        runner.awaitTermination(process)

        process.calls shouldContainExactly listOf("destroyForcibly", "waitFor")
    }

    test("종료 대기가 인터럽트로 깨지면 종료를 확인할 때까지 다시 기다린다") {
        val process = RecordingProcess(interruptionsBeforeExit = 2)

        runner.awaitTermination(process)

        process.calls shouldContainExactly listOf("destroyForcibly", "waitFor", "waitFor", "waitFor")
        Thread.currentThread().isInterrupted shouldBe false
    }

    test("고정 시간 제한 없이 실행을 유지하다가 호출자 취소로만 끝난다") {
        val pidFile = tempdir().toPath().resolve("pid.txt")
        val command = ExternalToolTestProcess.command(
            ExternalToolTestProcess.SLEEP_FOREVER,
            pidFile.toString(),
        )
        var propagated: Throwable? = null

        coroutineScope {
            val execution = launch(Dispatchers.Default) {
                try {
                    runner.run(command)
                } catch (cancellation: CancellationException) {
                    propagated = cancellation
                    throw cancellation
                }
            }
            val pid = withTimeout(PROCESS_START_WAIT_MILLIS) { awaitRecordedPid(pidFile) }

            // 대화형 병합은 몇 분씩 걸린다. 취소 전까지는 run 이 스스로 실행을 끊지 않아야 한다.
            delay(NO_TIME_LIMIT_OBSERVATION_MILLIS)
            isAlive(pid) shouldBe true
            execution.isActive shouldBe true

            execution.cancelAndJoin()

            propagated.shouldBeInstanceOf<CancellationException>()
            // 취소가 돌아온 시점에 프로세스가 이미 죽어 있어야 호출자의 임시 파일 정리와 경합하지 않는다.
            isAlive(pid) shouldBe false
        }
    }

    test("도구가 띄운 자식 프로세스도 취소가 돌아오기 전에 끝낸다") {
        val directory = tempdir().toPath()
        val childPidFile = directory.resolve("child-pid.txt")
        val parentPidFile = directory.resolve("parent-pid.txt")
        val command = ExternalToolTestProcess.command(
            ExternalToolTestProcess.SPAWN_CHILD,
            childPidFile.toString(),
            parentPidFile.toString(),
        )

        coroutineScope {
            val execution = launch(Dispatchers.Default) { runner.run(command) }
            val parentPid = withTimeout(PROCESS_START_WAIT_MILLIS) { awaitRecordedPid(parentPidFile) }
            val childPid = withTimeout(PROCESS_START_WAIT_MILLIS) { awaitRecordedPid(childPidFile) }

            execution.cancelAndJoin()

            isAlive(parentPid) shouldBe false
            // 살아남은 자식은 호출자가 지우려는 임시 파일을 계속 잡는다.
            isAlive(childPid) shouldBe false
        }
    }

    test("시작과 대기 사이에 취소가 끼어들어도 프로세스를 남기지 않는다") {
        val directory = tempdir().toPath()

        // 취소가 시작~대기 구간 어디에 떨어지는지는 정할 수 없다. 창을 훑으며 반복해 그 구간을 덮는다.
        repeat(START_CANCEL_ATTEMPTS) { attempt ->
            coroutineScope {
                val pidFile = directory.resolve("pid-$attempt.txt")
                val execution = launch(Dispatchers.Default) {
                    runner.run(
                        ExternalToolTestProcess.command(
                            ExternalToolTestProcess.SLEEP_FOREVER,
                            pidFile.toString(),
                        ),
                    )
                }

                delay(attempt.toLong() % START_CANCEL_JITTER_MILLIS)
                execution.cancelAndJoin()
            }
        }

        // 새어 나간 프로세스는 오래 사는 대역이라 pid 를 적고 계속 살아 있다.
        delay(LEAK_SETTLE_MILLIS)
        recordedPids(directory).filter { pid -> isAlive(pid) } shouldContainExactly emptyList()
    }
})

/**
 * 종료 요청·종료 확인의 **순서**는 실제 프로세스로는 결정적으로 관측할 수 없다 (SIGKILL 이 곧바로
 * 먹으면 기다리지 않는 구현도 통과한다). 순서와 인터럽트 재대기는 이 가짜 프로세스로 못 박는다.
 */
private class RecordingProcess(private val interruptionsBeforeExit: Int = 0) : Process() {

    val calls = mutableListOf<String>()

    private var remainingInterruptions = interruptionsBeforeExit

    override fun destroyForcibly(): Process {
        calls += "destroyForcibly"
        return this
    }

    override fun waitFor(): Int {
        calls += "waitFor"
        if (remainingInterruptions > 0) {
            remainingInterruptions -= 1
            Thread.currentThread().interrupt()
            throw InterruptedException("interrupted while waiting")
        }
        return 0
    }

    /** 커스텀 [Process] 는 핸들을 노출하지 않는다. 자손 없는 프로세스로 두어 부모 종료 순서만 관측한다. */
    override fun descendants(): Stream<ProcessHandle> = Stream.empty()

    override fun destroy() = Unit

    override fun exitValue(): Int = 0

    override fun isAlive(): Boolean = false

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
}

/**
 * PATH 해석 자체를 그 플랫폼이 실제로 가진 것으로 검증한다. 특정 도구 이름(`sh` 등)을 박으면
 * 그 이름이 없는 플랫폼에서 검증이 사라진다.
 */
private fun firstExecutableNameOnPath(): String? =
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        .orEmpty()
        .asSequence()
        .filter { directory -> directory.isNotBlank() }
        // PATH 에는 존재하지 않거나 읽을 수 없는 항목이 섞인다 — 그 항목 때문에 탐색이 끊기면 안 된다.
        .flatMap { directory -> listFiles(Path.of(directory)).asSequence() }
        .firstOrNull { path -> Files.isRegularFile(path) && Files.isExecutable(path) }
        ?.fileName
        ?.toString()

private fun listFiles(directory: Path): List<Path> =
    runCatching { Files.list(directory).use { paths -> paths.toList() } }.getOrDefault(emptyList())

private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { handle -> handle.isAlive }.orElse(false)

private fun recordedPids(directory: Path): List<Long> =
    listFiles(directory).mapNotNull { path -> runCatching { Files.readString(path).trim().toLongOrNull() }.getOrNull() }

private suspend fun awaitRecordedPid(pidFile: Path): Long {
    while (true) {
        val pid = runCatching { Files.readString(pidFile).trim().toLongOrNull() }.getOrNull()
        if (pid != null) return pid
        delay(POLL_INTERVAL_MILLIS)
    }
}
