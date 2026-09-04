package dev.undine.infrastructure.git.signing

import dev.undine.domain.signing.SigningCommandResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val PROBE_TIMEOUT_SECONDS = 10L
private const val UNRESPONSIVE_TIMEOUT_SECONDS = 1L
private const val TEST_TIMEOUT_SECONDS = 10L
private const val PATIENT_TIMEOUT_SECONDS = 60L
private const val STUB_SLEEP_SECONDS = 30

/** 스텁이 살아 있는 시간보다 훨씬 짧다 — 취소가 자식의 남은 수명을 기다리면 이 선을 넘는다. */
private const val CANCELLATION_DEADLINE_MILLIS = 10_000L
private const val SIGNATURE_FILE_SUFFIX = ".sig"
private const val SSH_SIGNATURE_HEADER = "-----BEGIN SSH SIGNATURE-----"
private const val STANDARD_INPUT_FAILURE = "표준 입력을 쓸 수 없습니다"

/** 파이프 버퍼(리눅스·macOS 기본 64KiB)보다 확실히 큰 크기다 — 비우지 않으면 자식이 그 자리에서 멈춘다. */
private const val PIPE_OVERFLOW_BYTES = 512 * 1024

/**
 * 프로세스 경계 구현 자체를 실행해 검증한다. Gateway 테스트는 가짜 실행기를 끼우므로 이 파일의
 * 표준 입출력·실행 불가·시간 초과·임시 파일 수명은 거기서 한 번도 실행되지 않는다.
 *
 * 외부 서명 도구(gpg·agent)에 의존하지 않는다. SSH 임시 파일 수명은 `ssh-keygen` 흉내만 내는
 * 스텁으로 어느 환경에서나 검증하고, 실제 `ssh-keygen` 을 쓰는 통합 경로만 도구가 없으면
 * 건너뛴다 (WAVE7-DECISIONS D3).
 */
class ProcessSigningCommandRunnerSpec : FunSpec({

    test("표준 입력으로 넘긴 서명 대상 바이트를 프로그램에 전달하고 표준 출력을 수집한다") {
        val result = ProcessSigningCommandRunner().run(listOf("cat"), "commit payload".toByteArray())

        result shouldBe SigningCommandResult.Completed(0, "commit payload", "")
    }

    test("프로그램이 비정상 종료해도 실행은 됐으므로 종료 코드와 표준 오류를 담아 돌려준다") {
        val missing = File(tempdir(), "undine-no-such-input").path

        val result = ProcessSigningCommandRunner().run(listOf("cat", missing), ByteArray(0))

        val completed = result.shouldBeInstanceOf<SigningCommandResult.Completed>()
        completed.exitCode shouldNotBe 0
        completed.standardError shouldContain "undine-no-such-input"
    }

    test("실행 파일이 없으면 서명 실패가 아니라 실행 불가로 구분해 돌려준다") {
        val result = ProcessSigningCommandRunner().run(listOf("undine-no-such-signing-program"), ByteArray(0))

        result shouldBe SigningCommandResult.NotExecutable("undine-no-such-signing-program")
    }

    test("응답하지 않는 프로그램은 강제 종료하고 중단으로 돌려준다") {
        val result = ProcessSigningCommandRunner(UNRESPONSIVE_TIMEOUT_SECONDS)
            .run(listOf("sleep", "30"), ByteArray(0))

        result.shouldBeInstanceOf<SigningCommandResult.Interrupted>()
    }

    test("stdin을 보유한 손자가 큰 입력을 읽지 않아도 시간 초과 후 종료된다") {
        val childPidFile = File(tempdir(), "stdin-holder.pid")
        val runner = ProcessSigningCommandRunner(UNRESPONSIVE_TIMEOUT_SECONDS)

        try {
            val result = withTimeout(TEST_TIMEOUT_SECONDS * 1_000) {
                runner.run(stdinHoldingDescendantCommand(childPidFile), ByteArray(PIPE_OVERFLOW_BYTES))
            }

            result.shouldBeInstanceOf<SigningCommandResult.Interrupted>()
            childPidFile.isFile shouldBe true
        } finally {
            childPidFile.takeIf(File::isFile)
                ?.readText()
                ?.trim()
                ?.toLongOrNull()
                ?.let(ProcessHandle::of)
                ?.ifPresent(ProcessHandle::destroyForcibly)
        }
    }

    test("표준 출력이 파이프 버퍼보다 커도 교착 없이 전량 수집한다") {
        val payload = "o".repeat(PIPE_OVERFLOW_BYTES)

        val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS).run(listOf("cat"), payload.toByteArray())

        // 기다린 뒤에 읽으면 자식이 표준 출력 쓰기에서 멈춘 채로 제한 시간을 넘겨 Interrupted 가 된다.
        result shouldBe SigningCommandResult.Completed(0, payload, "")
    }

    test("표준 오류가 파이프 버퍼보다 커도 교착 없이 전량 수집한다") {
        val payload = "e".repeat(PIPE_OVERFLOW_BYTES)

        val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS)
            .run(listOf("sh", "-c", "cat >&2"), payload.toByteArray())

        val completed = result.shouldBeInstanceOf<SigningCommandResult.Completed>()
        completed.exitCode shouldBe 0
        completed.standardError shouldBe payload
    }

    test("취소되면 자식 프로세스를 끊고 CancellationException 을 그대로 전파하며 임시 파일도 남기지 않는다") {
        val stub = stubSshKeygen(tempdir(), sleepSeconds = STUB_SLEEP_SECONDS)
        val temporaryFiles = RecordingTemporaryFiles()
        val started = CompletableDeferred<Process>()
        // 제한 시간이 아니라 취소가 종료 사유임을 확실히 하려고 스텁의 수명보다 넉넉히 잡는다.
        val runner = ProcessSigningCommandRunner(
            PATIENT_TIMEOUT_SECONDS,
            temporaryFiles,
            SigningProcessStarter { command -> SystemSigningProcessStarter.start(command).also(started::complete) },
        )
        var propagated: Throwable? = null

        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                runner.run(sshSignCommand(stub.path), "commit payload".toByteArray())
            } catch (cancellation: CancellationException) {
                // 삼키지 않고 다시 던진다 — 잡는 이유는 타입을 단정하기 위해서다.
                propagated = cancellation
                throw cancellation
            }
        }
        val process = started.await()
        // 자식의 남은 수명(STUB_SLEEP_SECONDS)만큼 기다리면 취소가 아니라 자연 종료를 본 것이다.
        withTimeout(CANCELLATION_DEADLINE_MILLIS) { job.cancelAndJoin() }

        propagated.shouldBeInstanceOf<CancellationException>()
        process.isAlive shouldBe false
        temporaryFiles.assertCreatedFilesDeleted()
    }

    test("SSH 서명은 임시 파일로 넘기고 서명 결과를 수집한 뒤 임시 파일을 남기지 않는다") {
        val temporaryFiles = RecordingTemporaryFiles()

        val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS, temporaryFiles)
            .run(sshSignCommand(stubSshKeygen(tempdir()).path), "commit payload".toByteArray())

        val completed = result.shouldBeInstanceOf<SigningCommandResult.Completed>()
        completed.exitCode shouldBe 0
        completed.standardOutput shouldContain SSH_SIGNATURE_HEADER
        temporaryFiles.assertCreatedFilesDeleted()
    }

    test("SSH 임시 파일을 만든 뒤 응답이 없으면 강제 종료 후 임시 파일을 남기지 않는다") {
        val stub = stubSshKeygen(tempdir(), sleepSeconds = STUB_SLEEP_SECONDS)
        val temporaryFiles = RecordingTemporaryFiles()

        val result = ProcessSigningCommandRunner(UNRESPONSIVE_TIMEOUT_SECONDS, temporaryFiles)
            .run(sshSignCommand(stub.path), "commit payload".toByteArray())

        result.shouldBeInstanceOf<SigningCommandResult.Interrupted>()
        temporaryFiles.assertCreatedFilesDeleted()
    }

    test("임시 파일에 쓰지 못하면 만들어 둔 파일을 지우고 서명 실패 사유로 돌려준다") {
        val temporaryFiles = RecordingTemporaryFiles(failWriteWith = IOException("장치에 남은 공간이 없습니다"))

        val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS, temporaryFiles)
            .run(sshSignCommand(stubSshKeygen(tempdir()).path), "commit payload".toByteArray())

        result.shouldBeInstanceOf<SigningCommandResult.Interrupted>().detail shouldContain "남은 공간이 없습니다"
        temporaryFiles.assertCreatedFilesDeleted()
    }

    test("서명 파일을 읽지 못하면 서명 성공으로 오보고하지 않고 임시 파일을 정리한다") {
        val temporaryFiles = RecordingTemporaryFiles(failReadWith = IOException("서명 파일을 읽을 권한이 없습니다"))

        val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS, temporaryFiles)
            .run(sshSignCommand(stubSshKeygen(tempdir()).path), "commit payload".toByteArray())

        result.shouldBeInstanceOf<SigningCommandResult.Interrupted>().detail shouldContain "읽을 권한이 없습니다"
        temporaryFiles.assertCreatedFilesDeleted()
    }

    test("SSH 서명의 표준 입력 I/O가 실패하면 중단 사유로 돌리고 임시 파일 쌍을 정리한다") {
        val temporaryFiles = RecordingTemporaryFiles()

        val result = ProcessSigningCommandRunner(
            TEST_TIMEOUT_SECONDS,
            temporaryFiles,
            SigningProcessStarter { command ->
                val payload = Path.of(command.last())
                Files.writeString(payload.signatureFile(), "signature")
                StandardInputFailureProcess()
            },
        ).run(sshSignCommand(stubSshKeygen(tempdir()).path), "commit payload".toByteArray())

        result.shouldBeInstanceOf<SigningCommandResult.Interrupted>().detail shouldContain STANDARD_INPUT_FAILURE
        temporaryFiles.assertCreatedFilesDeleted()
    }

    test("임시 파일 한쪽을 지우지 못해도 다른 쪽은 독립적으로 정리한다") {
        val temporaryFiles = RecordingTemporaryFiles(failDeleteOf = { path -> path.isPayload() })

        val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS, temporaryFiles)
            .run(sshSignCommand(stubSshKeygen(tempdir()).path), "commit payload".toByteArray())

        result.shouldBeInstanceOf<SigningCommandResult.Completed>().exitCode shouldBe 0
        val payload = temporaryFiles.createdFiles.single()
        Files.exists(payload.signatureFile()) shouldBe false
        Files.exists(payload) shouldBe true
        Files.deleteIfExists(payload)
    }

    test("서명이 임시 파일을 남기면 기록 기반 정리 검사가 실패한다") {
        val temporaryFiles = RecordingTemporaryFiles(failDeleteOf = { path -> path.isPayload() })

        ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS, temporaryFiles)
            .run(sshSignCommand(stubSshKeygen(tempdir()).path), "commit payload".toByteArray())

        // 좁힌 검사가 여전히 누수를 잡는다는 근거다 — 잡지 못하면 여기서 아무 예외도 나지 않는다.
        shouldThrow<AssertionError> { temporaryFiles.assertCreatedFilesDeleted() }
        temporaryFiles.createdFiles.forEach(Files::deleteIfExists)
    }

    test("같은 접두사의 임시 파일을 다른 프로세스가 남겨도 자기 서명의 정리 검사에 섞이지 않는다") {
        val foreign = Files.createTempFile(TEMPORARY_FILE_PREFIX, PAYLOAD_FILE_SUFFIX)
        val temporaryFiles = RecordingTemporaryFiles()

        try {
            val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS, temporaryFiles)
                .run(sshSignCommand(stubSshKeygen(tempdir()).path), "commit payload".toByteArray())

            result.shouldBeInstanceOf<SigningCommandResult.Completed>().exitCode shouldBe 0
            temporaryFiles.assertCreatedFilesDeleted()
        } finally {
            Files.deleteIfExists(foreign)
        }
    }

    test("실제 ssh-keygen 으로 서명해도 임시 파일을 남기지 않는다")
        .config(enabled = supportsSshSigning()) {
            val key = File(tempdir(), "signing_key").also(::generateSigningKey)
            val temporaryFiles = RecordingTemporaryFiles()

            val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS, temporaryFiles).run(
                sshSignCommand(program = "ssh-keygen", key = key.path),
                "commit payload".toByteArray(),
            )

            val completed = result.shouldBeInstanceOf<SigningCommandResult.Completed>()
            completed.exitCode shouldBe 0
            completed.standardOutput shouldContain SSH_SIGNATURE_HEADER
            temporaryFiles.assertCreatedFilesDeleted()
        }

    test("실제 ssh-keygen 서명이 실패해도 임시 파일을 남기지 않는다")
        .config(enabled = supportsSshSigning()) {
            val missingKey = File(tempdir(), "undine-no-such-key").path
            val temporaryFiles = RecordingTemporaryFiles()

            val result = ProcessSigningCommandRunner(TEST_TIMEOUT_SECONDS, temporaryFiles).run(
                sshSignCommand(program = "ssh-keygen", key = missingKey),
                "commit payload".toByteArray(),
            )

            result.shouldBeInstanceOf<SigningCommandResult.Completed>().exitCode shouldNotBe 0
            temporaryFiles.assertCreatedFilesDeleted()
        }
})

private fun sshSignCommand(program: String, key: String = "unused-key"): List<String> =
    listOf(program, "-Y", "sign", "-n", "git", "-f", key, "-")

/** 부모를 강제 종료해도 stdin read end를 보유하는 손자가 남는 종료 경로를 만든다. */
private fun stdinHoldingDescendantCommand(childPidFile: File): List<String> =
    listOf(
        "sh",
        "-c",
        "sleep $STUB_SLEEP_SECONDS & echo ${'$'}! > \"${'$'}1\"; wait",
        "sh",
        childPidFile.path,
    )

/**
 * `ssh-keygen -Y sign` 의 파일 규약만 흉내 내는 스텁이다 — 마지막 인자로 받은 서명 대상 파일
 * 옆에 `.sig` 를 만든다. 실제 도구가 없는 환경에서도 임시 파일 수명을 검증하려면 서명 파일을
 * 만들고 원하는 만큼 살아 있는 프로그램이 필요하다.
 */
private fun stubSshKeygen(directory: File, sleepSeconds: Int = 0): File {
    val stub = File(directory, "ssh-keygen")
    stub.writeText(
        """
        #!/bin/sh
        for payload; do :; done
        printf '%s' '$SSH_SIGNATURE_HEADER' > "${'$'}payload$SIGNATURE_FILE_SUFFIX"
        ${if (sleepSeconds > 0) "sleep $sleepSeconds" else ""}
        """.trimIndent(),
    )
    check(stub.setExecutable(true)) { "스텁을 실행 가능하게 만들지 못했습니다: ${stub.path}" }
    return stub
}

/**
 * 실패를 주입할 수 있는 임시 파일 경계. 주입한 실패 외에는 실제 파일 시스템에 위임하므로,
 * "잔재가 남지 않는다" 를 실제 파일 존재로 확인할 수 있다.
 *
 * **만든 경로를 만든 시점에 기록한다.** 정리 여부는 이 기록만 보고 판정한다 — 공용 임시
 * 디렉터리를 접두사로 훑으면 같은 접두사를 쓰는 다른 빌드의 파일이 섞여 들어와, 아무 잘못
 * 없이 정리 단언이 깨진다 (UND-90).
 */
private class RecordingTemporaryFiles(
    private val failWriteWith: IOException? = null,
    private val failReadWith: IOException? = null,
    private val failDeleteOf: (Path) -> Boolean = { false },
) : SigningTemporaryFiles {

    val createdFiles = mutableListOf<Path>()

    override fun createPayloadFile(): Path =
        SystemSigningTemporaryFiles.createPayloadFile().also(createdFiles::add)

    override fun write(path: Path, bytes: ByteArray) {
        failWriteWith?.let { failure -> throw failure }
        SystemSigningTemporaryFiles.write(path, bytes)
    }

    override fun readIfExists(path: Path): String? {
        failReadWith?.let { failure -> throw failure }
        return SystemSigningTemporaryFiles.readIfExists(path)
    }

    override fun deleteIfExists(path: Path) {
        if (failDeleteOf(path)) throw IOException("지울 수 없습니다: $path")
        SystemSigningTemporaryFiles.deleteIfExists(path)
    }
}

private fun RecordingTemporaryFiles.assertCreatedFilesDeleted() {
    // 기록이 비어 있으면 아래 단언이 통째로 공허하게 통과한다 — 무엇도 검사하지 않은 초록불이다.
    createdFiles.shouldNotBeEmpty()
    createdFiles.forEach { payload ->
        Files.exists(payload) shouldBe false
        Files.exists(payload.signatureFile()) shouldBe false
    }
}

/** 표준 입력 쓰기만 실패시키는 프로세스. 종료 상태라 finally의 즉시 정리 경로도 함께 검증한다. */
private class StandardInputFailureProcess : Process() {

    override fun getOutputStream(): OutputStream = object : OutputStream() {

        override fun write(byte: Int) {
            throw IOException(STANDARD_INPUT_FAILURE)
        }

        override fun close() {
            throw IOException(STANDARD_INPUT_FAILURE)
        }
    }

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false
}

/**
 * `-Y sign` 은 OpenSSH 8.0 부터다. 없는 환경에서 실패시키지 않고, 있다고 가정하지도 않는다.
 */
private fun supportsSshSigning(): Boolean =
    try {
        val process = ProcessBuilder("ssh-keygen", "-Y", "sign").redirectErrorStream(true).start()
        val usage = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        !usage.contains("unknown option")
    } catch (_: IOException) {
        false
    }

/** 패스프레이즈 없는 테스트 전용 키다. agent 도 사용자 키도 건드리지 않는다. */
private fun generateSigningKey(key: File) {
    val process = ProcessBuilder(
        "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "undine-signing-test", "-f", key.path,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    check(process.waitFor() == 0) { "테스트용 SSH 키를 만들지 못했습니다: $output" }
}

private fun Path.isPayload(): Boolean = fileName.toString().endsWith(PAYLOAD_FILE_SUFFIX)

private fun Path.signatureFile(): Path = resolveSibling("$fileName$SIGNATURE_FILE_SUFFIX")
