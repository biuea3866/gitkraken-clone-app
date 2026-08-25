package dev.undine.infrastructure.git.signing

import dev.undine.domain.signing.SigningCommandResult
import dev.undine.domain.signing.SigningCommandRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val PROCESS_TIMEOUT_SECONDS = 30L
private const val TERMINATION_TIMEOUT_SECONDS = 5L
private const val SIGNATURE_FILE_SUFFIX = ".sig"
private const val TIMEOUT_DETAIL = "서명 프로그램 응답 시간이 초과됐습니다."
private const val FILE_FAILURE_DETAIL = "서명에 필요한 임시 파일을 다루지 못했습니다."

/**
 * gpg·ssh-keygen·git 검증을 기존 사용자 환경에 위임하는 프로세스 구현.
 *
 * 명령은 셸을 거치지 않는 인자 배열로만 실행한다. 패스프레이즈·키 파일의 내용은 이 경계에
 * 전달되지 않으며, 입력은 서명 대상 Git 객체 바이트뿐이다.
 *
 * [timeoutSeconds] 를 열어 두는 이유는 응답하지 않는 프로그램에서의 동작을 테스트가 실제로
 * 재현할 수 있어야 하기 때문이다 — 기본값은 사람이 agent 잠금을 해제할 시간을 감안한 값이다.
 */
class ProcessSigningCommandRunner internal constructor(
    private val timeoutSeconds: Long,
    private val temporaryFiles: SigningTemporaryFiles,
    private val processStarter: SigningProcessStarter = SystemSigningProcessStarter,
) : SigningCommandRunner {

    constructor(timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS) : this(
        timeoutSeconds,
        SystemSigningTemporaryFiles,
        SystemSigningProcessStarter,
    )

    override suspend fun run(command: List<String>, standardInput: ByteArray): SigningCommandResult =
        withContext(Dispatchers.IO) {
            val prepared = try {
                prepare(command, standardInput)
            } catch (failure: IOException) {
                return@withContext failure.toInterrupted()
            }
            val process = try {
                processStarter.start(prepared.command)
            } catch (_: IOException) {
                // 프로그램이 시작조차 못 했으므로 파일에 손댈 주체가 없다 — 바로 지운다.
                temporaryFiles.deleteIgnoringFailure(prepared.temporaryFilePaths)
                return@withContext SigningCommandResult.NotExecutable(command.firstOrNull().orEmpty())
            }

            try {
                process.collectResult(prepared)
            } finally {
                temporaryFiles.deleteWhenSettled(process, prepared.temporaryFilePaths)
            }
        }

    /**
     * 표준 출력·표준 오류를 **비우면서** 종료를 기다린다.
     *
     * 순서가 바뀌면 안 된다. 먼저 [Process.waitFor] 를 부르면 파이프 버퍼가 찬 순간 자식이 쓰기에서
     * 멈추고, 앱은 그 교착을 시간 초과로 잘못 보고한다 — 서명 실패가 아니라 서로 기다리는 상태다.
     * 표준 입력 쓰기도 같은 이유로 함께 진행한다: 자식이 다 읽기 전에는 끝나지 않기 때문이다.
     */
    private suspend fun Process.collectResult(prepared: PreparedCommand): SigningCommandResult = coroutineScope {
        val standardOutput = drainText(inputStream)
        val standardError = drainText(errorStream)
        val standardInput = feed(this@collectResult, prepared.standardInput)
        try {
            if (!awaitExit()) {
                terminate()
                return@coroutineScope SigningCommandResult.Interrupted(TIMEOUT_DETAIL)
            }
            val written = standardInput.await()
            val output = standardOutput.await()
            val error = standardError.await()
            // 스트림 실패를 먼저 판정한다 — 뒤로 미루면 반쪽만 읽은 출력을 성공으로 내보내게 된다.
            listOf(written, output, error).firstFailure()?.let { failure ->
                return@coroutineScope failure.toInterrupted()
            }

            SigningCommandResult.Completed(
                exitCode = exitValue(),
                standardOutput = prepared.signatureOutput() ?: output.text(),
                standardError = error.text(),
            )
        } catch (cancellation: CancellationException) {
            // 취소는 삼키지 않는다. 다만 되던지기 전에 자식을 끊어야 위 수집이 끝나고 임시 파일이 정리된다.
            terminate()
            throw cancellation
        } catch (failure: IOException) {
            // 파일·표준 입출력 실패를 그대로 올리면 "실패는 던지지 않는다" 는 SigningGateway.sign
            // 계약이 깨져 화면이 번역되지 않은 예외를 받는다 — 사유를 담아 결과로 돌려준다.
            terminate()
            failure.toInterrupted()
        }
    }

    /** 취소되면 대기 스레드를 끊어 [CancellationException] 으로 나온다 — 취소가 제한 시간만큼 늦지 않는다. */
    private suspend fun Process.awaitExit(): Boolean =
        runInterruptible { waitFor(timeoutSeconds, TimeUnit.SECONDS) }

    /** ssh-keygen은 표준 입력을 서명 대상 파일로 받지 않으므로, 파일 수명만 여기서 국소화한다. */
    private fun prepare(command: List<String>, standardInput: ByteArray): PreparedCommand {
        if (!command.isSshSignCommand()) return PreparedCommand(command, standardInput)

        val payload = temporaryFiles.createPayloadFile()
        try {
            temporaryFiles.write(payload, standardInput)
        } catch (failure: IOException) {
            // 여기서 되돌아가면 이 파일을 지울 책임을 질 호출부가 없다 — 만든 자리에서 끝낸다.
            temporaryFiles.deleteIgnoringFailure(listOf(payload))
            throw failure
        }
        return PreparedCommand(
            command = command.dropLast(1) + payload.toString(),
            standardInput = ByteArray(0),
            signatureFile = payload.resolveSibling("${payload.fileName}$SIGNATURE_FILE_SUFFIX"),
            payloadFile = payload,
        )
    }

    private fun PreparedCommand.signatureOutput(): String? = signatureFile?.let(temporaryFiles::readIfExists)
}

/** 프로세스 시작을 좁힌 경계. 표준 스트림 I/O 실패도 실제 외부 프로그램 없이 검증할 수 있다. */
internal fun interface SigningProcessStarter {

    fun start(command: List<String>): Process
}

internal object SystemSigningProcessStarter : SigningProcessStarter {

    override fun start(command: List<String>): Process = ProcessBuilder(command).start()
}

private class PreparedCommand(
    val command: List<String>,
    val standardInput: ByteArray,
    val signatureFile: Path? = null,
    val payloadFile: Path? = null,
) {
    val temporaryFilePaths: List<Path> = listOfNotNull(signatureFile, payloadFile)
}

/**
 * 표준 스트림 처리의 결과.
 *
 * 실패를 예외로 올리지 않고 값으로 들고 오는 이유는, 세 갈래가 동시에 도는 동안 한쪽 예외가
 * 나머지를 취소하면 **자식은 살아 있는데 읽기만 멈춰** 정리가 진행되지 못하기 때문이다.
 * 실패는 버리지 않고 호출부가 순서대로 판정한다.
 */
private sealed interface StreamOutcome<out T> {

    val failure: IOException?

    class Read<T>(val value: T) : StreamOutcome<T> {

        override val failure: IOException? get() = null
    }

    class Failed(override val failure: IOException) : StreamOutcome<Nothing>
}

private fun List<StreamOutcome<*>>.firstFailure(): IOException? =
    firstNotNullOfOrNull(StreamOutcome<*>::failure)

/** 실패는 [firstFailure] 가 이미 걸러 낸 뒤에만 부른다 — 여기서 빈 문자열이 실패를 가리지 않는다. */
private fun StreamOutcome<String>.text(): String = (this as? StreamOutcome.Read)?.value.orEmpty()

private inline fun <T> streamOutcome(block: () -> T): StreamOutcome<T> =
    try {
        StreamOutcome.Read(block())
    } catch (failure: IOException) {
        StreamOutcome.Failed(failure)
    }

/** 자식이 쓰는 즉시 읽어 파이프 버퍼가 차지 않게 한다. */
private fun CoroutineScope.drainText(stream: InputStream): Deferred<StreamOutcome<String>> =
    async(Dispatchers.IO) { streamOutcome { stream.readBytes().toString(Charsets.UTF_8) } }

/** 쓰기가 끝나면 닫는다 — 표준 입력을 열어 두면 입력이 끝나기를 기다리는 프로그램이 종료하지 않는다. */
private fun CoroutineScope.feed(process: Process, bytes: ByteArray): Deferred<StreamOutcome<Unit>> =
    async(Dispatchers.IO) { streamOutcome { process.outputStream.use { stream -> stream.write(bytes) } } }

/**
 * 강제 종료를 요청하고 실제로 죽을 시간을 짧게 준다.
 *
 * 제한 시간 안에 죽지 않는 경우를 여기서 판정하지 않는 이유는, 그 경우의 유일한 위험 — 죽는 중인
 * 자식이 서명 파일을 다시 쓰는 것과 임시 파일 정리의 경합 — 을 [deleteWhenSettled] 가 정리를
 * 실제 종료 시점으로 미뤄 이미 막고 있기 때문이다. 기다리는 것은 흔한 경우에 정리를 미루지 않으려는
 * 것뿐이다.
 */
private fun Process.terminate() {
    destroyForcibly()
    closeStreams()
    onExit().completeOnTimeout(this, TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS).join()
}

/** 자식이나 손자가 파이프를 물고 있으면 읽기·쓰기가 끝나지 않는다 — 수집을 끊어 준다. */
private fun Process.closeStreams() {
    listOf(inputStream, errorStream, outputStream).forEach { stream ->
        try {
            stream.close()
        } catch (_: IOException) {
            // 버릴 출력의 스트림이다 — 닫기 실패는 결과를 바꾸지 않는다.
        }
    }
}

private fun IOException.toInterrupted(): SigningCommandResult.Interrupted =
    SigningCommandResult.Interrupted("$FILE_FAILURE_DETAIL ${message.orEmpty()}".trim())

private const val SSH_KEYGEN_PROGRAM = "ssh-keygen"
private const val SSH_SIGN_COMMAND_MIN_SIZE = 8
private const val STANDARD_INPUT_ARGUMENT = "-"
private val SSH_SIGN_SUBCOMMAND = listOf("-Y", "sign")

/**
 * 서명 대상을 파일로만 받는 `ssh-keygen -Y sign` 형태인지 가른다.
 *
 * 프로그램은 이름이 아니라 경로로 올 수 있어(git 의 `gpg.ssh.program` 은 보통 절대 경로다)
 * 경로의 파일 이름으로 판정한다.
 */
private fun List<String>.isSshSignCommand(): Boolean =
    size >= SSH_SIGN_COMMAND_MIN_SIZE &&
        first().toProgramName() == SSH_KEYGEN_PROGRAM &&
        subList(1, 1 + SSH_SIGN_SUBCOMMAND.size) == SSH_SIGN_SUBCOMMAND &&
        last() == STANDARD_INPUT_ARGUMENT

private fun String.toProgramName(): String = Path.of(this).fileName?.toString().orEmpty()
