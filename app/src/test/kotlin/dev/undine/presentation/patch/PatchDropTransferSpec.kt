package dev.undine.presentation.patch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException

/**
 * OS 전송 객체에서 경로를 꺼내는 경로 — **실제 드래그 없이** 확인한다.
 *
 * 추출이 modifier 안에 있으면 "flavor 가 없다 · 전송이 실패한다 · 파일이 아니다" 세 갈래가 검증되지
 * 않은 채 남는다. 여기서는 [fileLocationsOf] 결과와 그 뒤 [patchDropOf] 판정까지 함께 본다.
 */
class PatchDropTransferSpec : FunSpec({

    test("파일 목록을 주는 전송에서 경로를 그대로 꺼낸다") {
        val transferable = FakeTransferable(data = listOf(File("/tmp/fix.patch")))

        fileLocationsOf(transferable) shouldContainExactly listOf("/tmp/fix.patch")
        patchDropOf(fileLocationsOf(transferable)).shouldBeInstanceOf<PatchDrop.Accepted>()
            .path.toString() shouldBe "/tmp/fix.patch"
    }

    test("파일 여럿을 주는 전송은 첫 것을 조용히 쓰지 않고 사유와 함께 거부된다") {
        val transferable = FakeTransferable(data = listOf(File("/tmp/a.patch"), File("/tmp/b.patch")))

        patchDropOf(fileLocationsOf(transferable)).shouldBeInstanceOf<PatchDrop.Rejected>()
            .reason shouldBe PatchDrop.Reason.MULTIPLE_FILES
    }

    test("파일 flavor 가 없는 전송은 파일을 못 찾은 것으로 안내된다") {
        val transferable = FakeTransferable(data = listOf(File("/tmp/fix.patch")), supportsFileList = false)

        fileLocationsOf(transferable).isEmpty() shouldBe true
        patchDropOf(fileLocationsOf(transferable)).shouldBeInstanceOf<PatchDrop.Rejected>()
            .reason shouldBe PatchDrop.Reason.NO_FILE
    }

    test("전송 객체가 아예 없으면 빈 목록으로 읽고 안내로 이어진다") {
        fileLocationsOf(null).isEmpty() shouldBe true
        patchDropOf(fileLocationsOf(null)).shouldBeInstanceOf<PatchDrop.Rejected>()
            .reason shouldBe PatchDrop.Reason.NO_FILE
    }

    test("지원한다고 답해 놓고 못 주는 전송도 화면을 깨뜨리지 않는다") {
        val transferable = FakeTransferable(failure = UnsupportedFlavorException(DataFlavor.javaFileListFlavor))

        fileLocationsOf(transferable).isEmpty() shouldBe true
        patchDropOf(fileLocationsOf(transferable)).shouldBeInstanceOf<PatchDrop.Rejected>()
            .reason shouldBe PatchDrop.Reason.NO_FILE
    }

    test("드롭 원본이 사라진 전송도 화면을 깨뜨리지 않는다") {
        val transferable = FakeTransferable(failure = IOException("원본이 사라짐"))

        fileLocationsOf(transferable).isEmpty() shouldBe true
        patchDropOf(fileLocationsOf(transferable)).shouldBeInstanceOf<PatchDrop.Rejected>()
            .reason shouldBe PatchDrop.Reason.NO_FILE
    }

    test("파일이 아닌 값이 섞여 오면 파일만 골라 낸다") {
        val transferable = FakeTransferable(data = listOf("문자열", File("/tmp/fix.patch")))

        fileLocationsOf(transferable) shouldContainExactly listOf("/tmp/fix.patch")
    }
})

/** AWT 전송 객체의 대역 — 실제 드래그 없이 flavor 유무와 전송 실패를 만들어 낸다. */
private class FakeTransferable(
    private val data: Any? = null,
    private val supportsFileList: Boolean = true,
    private val failure: Exception? = null,
) : Transferable {

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        if (supportsFileList) arrayOf(DataFlavor.javaFileListFlavor) else emptyArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        supportsFileList && flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any? {
        failure?.let { throw it }
        return data
    }
}
