package dev.undine.infrastructure.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val INSTALLER_NAME = "undine-2.0.0-linux.deb"

private fun installerIn(directory: Path): Path =
    directory.resolve(INSTALLER_NAME).also { Files.writeString(it, "설치 파일 내용") }

/**
 * 사슬 끝의 원인 — 어댑터가 감싼 **플랫폼 예외**다.
 *
 * `cause` 한 번으로 닿지 않는다: `kotlinx-coroutines` 의 스택트레이스 복원이 경계를 넘는 예외를
 * **복사**해 원본을 그 `cause` 에 넣기 때문에 `IOException(복사) → IOException(원본) → 플랫폼 예외`
 * 가 된다. 겹 수를 세지 말고 끝을 본다 — 복원이 켜지고 꺼지는 것에 단언이 흔들리지 않는다.
 */
private val Throwable.rootCause: Throwable
    get() = generateSequence(this) { it.cause }.last()

private fun openFailureOf(installer: Path, opener: DesktopFileOpener?): Throwable? =
    runCatching {
        runBlocking { DesktopInstallerOpener(fileOpener = { opener }).open(installer) }
    }.exceptionOrNull()

/**
 * `DesktopInstallerOpener` 의 실제 예외 변환.
 *
 * 게이트웨이는 `IOException` 만 잡아 `OpenFailed` 로 올린다 — 이 어댑터가 플랫폼 예외를 그대로
 * 흘리면 그 위의 catch 를 지나쳐 앱이 죽는다. fake `InstallerOpener` 로는 그 경계가 검증되지
 * 않는다 (결정 D23).
 */
class DesktopInstallerOpenerSpec : FunSpec({

    test("데스크톱 연동이 파일을 받으면 그대로 넘긴다") {
        val installer = installerIn(tempdir().toPath())
        val opened = mutableListOf<File>()

        runBlocking { DesktopInstallerOpener(fileOpener = { DesktopFileOpener(opened::add) }).open(installer) }

        opened shouldBe listOf(installer.toFile())
    }

    test("데스크톱 연동을 쓸 수 없으면 IOException 으로 올린다 — 열리지 않은 것을 성공으로 접지 않는다") {
        val installer = installerIn(tempdir().toPath())

        val failure = openFailureOf(installer, opener = null)

        failure.shouldBeInstanceOf<IOException>().message.orEmpty() shouldContain INSTALLER_NAME
    }

    test("플랫폼이 파일을 거절하면 IOException 으로 바꿔 올린다") {
        val installer = installerIn(tempdir().toPath())
        val rejecting = DesktopFileOpener { throw IllegalArgumentException("알 수 없는 파일") }

        val failure = openFailureOf(installer, rejecting)

        failure.shouldBeInstanceOf<IOException>().rootCause.shouldBeInstanceOf<IllegalArgumentException>()
    }

    test("플랫폼이 열기를 지원하지 않으면 IOException 으로 바꿔 올린다") {
        val installer = installerIn(tempdir().toPath())
        val unsupported = DesktopFileOpener { throw UnsupportedOperationException("지원하지 않습니다") }

        val failure = openFailureOf(installer, unsupported)

        failure.shouldBeInstanceOf<IOException>().rootCause.shouldBeInstanceOf<UnsupportedOperationException>()
    }

    test("연동을 얻는 단계에서 정책이 막아도 IOException 으로 바꿔 올린다") {
        val installer = installerIn(tempdir().toPath())

        val failure = runCatching {
            runBlocking {
                DesktopInstallerOpener(fileOpener = { throw SecurityException("정책이 막았다") }).open(installer)
            }
        }.exceptionOrNull()

        failure.shouldBeInstanceOf<IOException>().rootCause.shouldBeInstanceOf<SecurityException>()
    }

    test("여는 호출을 정책이 막아도 IOException 으로 바꿔 올린다") {
        val installer = installerIn(tempdir().toPath())
        val denied = DesktopFileOpener { throw SecurityException("정책이 막았다") }

        val failure = openFailureOf(installer, denied)

        failure.shouldBeInstanceOf<IOException>().rootCause.shouldBeInstanceOf<SecurityException>()
    }

    test("열기에 실패해도 파일을 지우지 않는다 — 실패한 것은 여는 동작뿐이다") {
        val installer = installerIn(tempdir().toPath())

        openFailureOf(installer, opener = null)

        Files.exists(installer) shouldBe true
    }
})
