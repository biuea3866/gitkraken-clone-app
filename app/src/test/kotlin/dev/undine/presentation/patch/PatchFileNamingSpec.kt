package dev.undine.presentation.patch

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

/** 커밋당 파일명 — `git format-patch` 관례를 따르는 순번 + 요약 슬러그. */
class PatchFileNamingSpec : FunSpec({

    test("순번은 네 자리로 채우고 요약은 소문자 하이픈 슬러그가 된다") {
        perCommitPatchFileName(1, "Fix the Patch Screen?") shouldBe "0001-fix-the-patch-screen.patch"
        perCommitPatchFileName(42, "add DI wiring") shouldBe "0042-add-di-wiring.patch"
    }

    test("요약이 비면 순번만으로 이름을 짓는다") {
        perCommitPatchFileName(1, null) shouldBe "0001.patch"
        perCommitPatchFileName(2, "   ") shouldBe "0002.patch"
        perCommitPatchFileName(3, "###") shouldBe "0003.patch"
    }

    test("여러 줄 메시지는 첫 줄만 쓰고 50자를 넘으면 자른다") {
        perCommitPatchFileName(1, "first line\n\nbody that must not appear") shouldBe "0001-first-line.patch"

        val long = perCommitPatchFileName(1, "a".repeat(80))
        long shouldBe "0001-${"a".repeat(50)}.patch"
        long shouldEndWith ".patch"
    }

    test("잘린 자리가 하이픈이면 남기지 않는다") {
        val summary = "${"a".repeat(49)} tail"

        perCommitPatchFileName(1, summary) shouldBe "0001-${"a".repeat(49)}.patch"
    }

    test("순번은 1부터다 — 0 이하는 호출부 버그라 막는다") {
        shouldThrow<IllegalArgumentException> { perCommitPatchFileName(0, "x") }
    }
})
