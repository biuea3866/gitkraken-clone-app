package dev.undine.infrastructure.git.lfs

import dev.undine.domain.lfs.LfsCommandOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.engine.spec.tempdir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

private const val FAILURE_EXIT_CODE = 7

/** 호출자 스레드를 이름으로 못박는다 — 해소가 여기서 돌면 IO 경계 밖이라는 뜻이다. */
private const val CALLER_THREAD_NAME = "undine-lfs-caller"

/** 파이프 버퍼(대개 64KB)를 훌쩍 넘겨야 '읽지 않으면 막힌다' 를 재현할 수 있다. */
private const val FLOOD_OUTPUT_BYTES = 4 * 1024 * 1024

/** 막히지 않는다면 이 안에 끝난다. 넘기면 실패로 드러나야지 테스트가 매달려서는 안 된다. */
private const val FLOOD_OUTPUT_TIMEOUT_MILLIS = 60_000L

private const val PROCESS_START_WAIT_MILLIS = 10_000L
private const val POLL_INTERVAL_MILLIS = 20L

/** 새어 나간 프로세스는 JVM 부팅을 마쳐야 pid 를 적는다. 그 시간을 준 뒤에 확인한다. */
private const val LEAK_SETTLE_MILLIS = 1_500L

/**
 * `git-lfs` 프로세스 경계 자체를 검증한다. **시스템 `git-lfs` 를 부르지 않는다** —
 * 테스트가 자기 JVM 을 대역으로 다시 띄운다 ([LfsTestProcess]).
 */
class ProcessLfsCommandRunnerSpec : FunSpec({

    test("실행 파일을 찾지 못하면 미설치로 보고한다") {
        val runner = runnerFor { listOf("undine-absent-git-lfs") + it }

        runner.run(LfsCommand.VERSION.arguments, tempdir().toPath()) shouldBe LfsCommandOutcome.NotInstalled
    }

    test("명령 해소와 실행 파일 탐색은 호출자 스레드가 아니라 IO 경계 안에서 돈다") {
        // 이름 해소는 PATH 를 뒤지는 파일시스템 작업이다. 밖에 두면 UI 코루틴에서 부를 때
        // 디스크 조회가 UI 스레드에서 돈다 — 옮긴 것이 되돌아가면 이 테스트가 잡는다.
        val resolutionThread = CompletableDeferred<Thread>()
        val runner = runnerFor { arguments ->
            resolutionThread.complete(Thread.currentThread())
            listOf("undine-absent-git-lfs") + arguments
        }
        // 호출자를 단일 스레드에 가둔다 — 해소가 그 스레드를 벗어났다면 IO 경계 안으로 들어간 것이다.
        val caller = Executors.newSingleThreadExecutor { task -> Thread(task, CALLER_THREAD_NAME) }
        val callerThread = CompletableDeferred<Thread>()

        val outcome = try {
            withContext(caller.asCoroutineDispatcher()) {
                callerThread.complete(Thread.currentThread())
                runner.run(LfsCommand.VERSION.arguments, tempdir().toPath())
            }
        } finally {
            caller.shutdown()
        }

        resolutionThread.await() shouldNotBe callerThread.await()
        // 탐색까지 그 안에서 끝났다는 근거 — PATH 조회 결과가 미설치로 나왔다.
        outcome shouldBe LfsCommandOutcome.NotInstalled
    }

    test("실행 권한이 없는 파일은 미설치가 아니라 시작 실패로 보고한다") {
        val plainFile = Files.writeString(tempdir().toPath().resolve("git-lfs"), "not executable")
        val runner = runnerFor { listOf(plainFile.toString()) + it }

        val outcome = runner.run(LfsCommand.VERSION.arguments, tempdir().toPath())

        outcome.shouldBeInstanceOf<LfsCommandOutcome.StartFailed>().detail shouldContain "실행 권한"
    }

    test("작업 디렉터리가 없으면 미설치가 아니라 시작 실패로 보고한다") {
        val runner = runnerFor { LfsTestProcess.emitting(standardOutput = null, exitCode = 0, argumentLog = null) + it }
        val missingDirectory = tempdir().toPath().resolve("사라진-작업-디렉터리")

        runner.run(LfsCommand.VERSION.arguments, missingDirectory)
            .shouldBeInstanceOf<LfsCommandOutcome.StartFailed>()
    }

    test("하위 명령 인자를 쪼개지 않고 그대로 전달하고 표준 출력을 돌려준다") {
        val work = tempdir().toPath()
        val outputFile = Files.writeString(work.resolve("out.txt"), "git-lfs/3.4.1\n")
        val argumentLog = work.resolve("arguments.txt")
        val runner = runnerFor { LfsTestProcess.emitting(outputFile, exitCode = 0, argumentLog = argumentLog) + it }

        val outcome = runner.run(LfsCommand.LS_FILES_WITH_SIZE.arguments, work)

        outcome.shouldBeInstanceOf<LfsCommandOutcome.Completed>().standardOutput shouldBe "git-lfs/3.4.1\n"
        Files.readAllLines(argumentLog) shouldContainExactly LfsCommand.LS_FILES_WITH_SIZE.arguments
    }

    test("비정상 종료 코드를 그대로 돌려준다") {
        val work = tempdir().toPath()
        val runner = runnerFor {
            LfsTestProcess.emitting(standardOutput = null, exitCode = FAILURE_EXIT_CODE, argumentLog = null) + it
        }

        runner.run(LfsCommand.LOCKS.arguments, work)
            .shouldBeInstanceOf<LfsCommandOutcome.Completed>().exitCode shouldBe FAILURE_EXIT_CODE
    }

    test("출력을 대량으로 쏟는 도구도 파이프에 막히지 않고 끝까지 실행된다") {
        val work = tempdir().toPath()
        val runner = runnerFor {
            LfsTestProcess.command(LfsTestProcess.FLOOD_OUTPUT, FLOOD_OUTPUT_BYTES.toString()) + it
        }

        // 기다리기 전에 비우지 않으면 자식이 쓰기에서 멈춰 waitFor 가 영영 돌아오지 않는다.
        withTimeout(FLOOD_OUTPUT_TIMEOUT_MILLIS) {
            runner.run(LfsCommand.FETCH.arguments, work)
                .shouldBeInstanceOf<LfsCommandOutcome.Completed>().exitCode shouldBe 0
        }
    }

    test("표준 출력은 한 줄씩 진행 콜백으로 전달된다") {
        val work = tempdir().toPath()
        val outputFile = Files.writeString(work.resolve("progress.txt"), "받는 중 1/2\n받는 중 2/2\n")
        val runner = runnerFor { LfsTestProcess.emitting(outputFile, exitCode = 0, argumentLog = null) + it }
        val progress = mutableListOf<String>()

        runner.run(LfsCommand.FETCH.arguments, work) { line -> progress += line }

        progress shouldContainExactly listOf("받는 중 1/2", "받는 중 2/2")
    }

    test("취소하면 프로세스와 그 자손이 모두 끝난 뒤에 취소가 전파된다") {
        val work = tempdir().toPath()
        val childPidFile = work.resolve("child.pid")
        val ownPidFile = work.resolve("own.pid")
        val runner = runnerFor {
            LfsTestProcess.command(
                LfsTestProcess.SPAWN_CHILD,
                childPidFile.toString(),
                ownPidFile.toString(),
            ) + it
        }
        var propagated: Throwable? = null

        coroutineScope {
            // 디스패처를 고르지 않는다 — run() 이 자기 안에서 Dispatchers.IO 로 옮긴다.
            val execution = launch {
                try {
                    runner.run(LfsCommand.FETCH.arguments, work)
                } catch (cancellation: CancellationException) {
                    propagated = cancellation
                    throw cancellation
                }
            }
            awaitFile(childPidFile)
            awaitFile(ownPidFile)
            execution.cancelAndJoin()
        }

        propagated.shouldNotBeNull()
        // 루트만 죽이면 자식이 살아남아 워킹트리를 계속 잡는다.
        delay(LEAK_SETTLE_MILLIS)
        isAlive(ownPidFile) shouldBe false
        isAlive(childPidFile) shouldBe false
    }
})

private fun runnerFor(commandLine: (List<String>) -> List<String>): ProcessLfsCommandRunner =
    ProcessLfsCommandRunner(LfsCommandLine { arguments -> commandLine(arguments) })

private suspend fun awaitFile(file: Path) {
    withTimeout(PROCESS_START_WAIT_MILLIS) {
        while (!Files.exists(file) || Files.readString(file).isBlank()) delay(POLL_INTERVAL_MILLIS)
    }
}

/** 사라진 핸들은 열거되지 않는다 — 그것도 '살아 있지 않음' 이다. */
private fun isAlive(pidFile: Path): Boolean =
    Files.readString(pidFile).trim().toLongOrNull()
        ?.let { pid -> ProcessHandle.of(pid).orElse(null) }
        ?.isAlive == true
