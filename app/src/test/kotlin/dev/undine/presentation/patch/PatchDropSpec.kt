package dev.undine.presentation.patch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 드롭 판정 — **실제 OS 드래그 없이** 확인한다.
 *
 * 판정이 modifier 안에 갇히면 "여러 파일을 거부한다" 를 검증할 길이 없다. 거부는 실패가 아니라
 * 안내이므로 예외를 던지지 않는다.
 */
class PatchDropSpec : FunSpec({

    test("파일 하나를 놓으면 그 경로를 받는다") {
        val accepted = patchDropOf(listOf("/tmp/fix.patch")).shouldBeInstanceOf<PatchDrop.Accepted>()

        accepted.path.toString() shouldBe "/tmp/fix.patch"
    }

    test("file URI 로 온 경로도 같은 파일로 읽는다") {
        val accepted = patchDropOf(listOf("file:///tmp/fix.patch")).shouldBeInstanceOf<PatchDrop.Accepted>()

        accepted.path.toString() shouldBe "/tmp/fix.patch"
    }

    test("놓은 것에 파일이 없으면 사유를 남기고 받지 않는다") {
        patchDropOf(emptyList()).shouldBeInstanceOf<PatchDrop.Rejected>().reason shouldBe
            PatchDrop.Reason.NO_FILE
        patchDropOf(listOf("https://example.com/fix.patch")).shouldBeInstanceOf<PatchDrop.Rejected>().reason shouldBe
            PatchDrop.Reason.NO_FILE
    }

    test("파일을 여럿 놓으면 첫 것을 조용히 쓰지 않고 사유와 함께 거부한다") {
        val rejected = patchDropOf(listOf("/tmp/a.patch", "/tmp/b.patch"))
            .shouldBeInstanceOf<PatchDrop.Rejected>()

        rejected.reason shouldBe PatchDrop.Reason.MULTIPLE_FILES
    }
})
