package dev.undine.infrastructure.diagnostics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.sequences.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.io.IOException

/**
 * 실제 파일 관리자를 띄우는 경계라 **환경 판정과 전달 대상만** 검증한다 — 테스트가 개발자의
 * Finder·탐색기를 실제로 열면 안 된다. 데스크톱 연동을 쓸 수 없는 환경에서 조용히 성공하지 않는 것이
 * 이 경계의 계약이다.
 */
class DesktopFileManagerLauncherSpec : FunSpec({

    test("데스크톱 연동을 쓸 수 없으면 조용히 성공하지 않고 사유가 있는 실패를 던진다") {
        val directory = tempdir().toPath()
        val launcher = DesktopFileManagerLauncher(directoryOpener = { null })

        val failure = shouldThrow<IOException> { launcher.open(directory) }

        failure.message.orEmpty() shouldContain directory.toString()
    }

    test("플랫폼이 경로를 거부하면 사유와 원인을 담은 실행 실패로 바꿔 올린다") {
        val directory = tempdir().toPath().resolve("사라진-디렉터리")
        val rejection = IllegalArgumentException("파일이 없습니다")
        val launcher = DesktopFileManagerLauncher(
            directoryOpener = { DirectoryOpener { throw rejection } },
        )

        val failure = shouldThrow<IOException> { launcher.open(directory) }

        failure.message.orEmpty() shouldContain directory.toString()
        // 코루틴이 호출 지점 스택을 복원하며 예외 사본을 만들므로 원인은 사슬로 확인한다.
        generateSequence<Throwable>(failure) { throwable -> throwable.cause } shouldContain rejection
    }

    test("데스크톱 연동을 쓸 수 있으면 받은 경로를 그대로 넘긴다") {
        val directory = tempdir().toPath()
        var opened: File? = null
        val launcher = DesktopFileManagerLauncher(directoryOpener = { DirectoryOpener { file -> opened = file } })

        launcher.open(directory)

        opened shouldBe directory.toFile()
    }
})
