package dev.undine.infrastructure.git.signing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * 임시 파일 정리 시점을 검증한다.
 *
 * 강제 종료가 확인되지 않는 경로는 실제 프로그램으로 재현할 수 없다 — 강제 종료는 거의 언제나
 * 즉시 듣는다. 그래서 정리 시점 판단만 [Process] 경계에서 떼어 내 확인한다.
 */
class SigningTemporaryFilesSpec : FunSpec({

    test("이미 끝난 프로그램의 임시 파일은 바로 지운다") {
        val files = temporaryFilePair()
        val process = FakeProcess().also(FakeProcess::finish)

        SystemSigningTemporaryFiles.deleteWhenSettled(process, files)

        files.forEach { file -> Files.exists(file) shouldBe false }
    }

    test("종료가 확인되지 않으면 프로그램이 실제로 끝난 뒤에 임시 파일을 지운다") {
        val files = temporaryFilePair()
        val process = FakeProcess()

        SystemSigningTemporaryFiles.deleteWhenSettled(process, files)

        // 살아 있는 동안 지우면 죽는 중인 프로그램이 서명 파일을 다시 만들어 잔재가 남는다.
        files.forEach { file -> Files.exists(file) shouldBe true }

        process.finish()

        files.forEach { file -> Files.exists(file) shouldBe false }
    }

    test("한쪽을 지우지 못해도 나머지 임시 파일은 지운다") {
        val files = temporaryFilePair()
        val stubborn = files.first()

        UndeletableTemporaryFiles(stubborn).deleteIgnoringFailure(files)

        Files.exists(stubborn) shouldBe true
        Files.exists(files.last()) shouldBe false
        Files.deleteIfExists(stubborn)
    }
})

/** 서명 경로가 만드는 것과 같은 짝 — 서명 파일과 payload 파일이다. */
private fun temporaryFilePair(): List<Path> {
    val payload = SystemSigningTemporaryFiles.createPayloadFile()
    val signature = payload.resolveSibling("${payload.fileName}.sig")
    Files.write(signature, "signature".toByteArray())
    return listOf(signature, payload)
}

private class UndeletableTemporaryFiles(
    private val undeletable: Path,
) : SigningTemporaryFiles by SystemSigningTemporaryFiles {

    override fun deleteIfExists(path: Path) {
        if (path == undeletable) throw IOException("지울 수 없습니다: $path")
        SystemSigningTemporaryFiles.deleteIfExists(path)
    }
}

/** 종료 시점을 테스트가 정하는 프로그램. 실제 프로그램으로는 이 시점을 제어할 수 없다. */
private class FakeProcess : Process() {

    private val exit = CompletableFuture<Process>()
    private var running = true

    fun finish() {
        running = false
        exit.complete(this)
    }

    override fun isAlive(): Boolean = running

    override fun onExit(): CompletableFuture<Process> = exit

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() = Unit
}
