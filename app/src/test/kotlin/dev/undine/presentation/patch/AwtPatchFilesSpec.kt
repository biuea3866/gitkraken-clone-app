package dev.undine.presentation.patch

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 저장 경계의 쓰기 — **실제 파일시스템**에서 확인한다.
 *
 * 대역으로는 "임시 파일을 거쳐 바꿔치기한다" 를 확인할 수 없다. 여기서 보는 것은 세 가지다:
 * 쓰기가 끝난 뒤 임시 파일이 남지 않는가, 쓰지 못했을 때 기존 파일이 그대로인가, 그리고 임시 파일을
 * **지우지도 못했을 때** 그 사실이 실패에 실려 올라오는가.
 *
 * 마지막 하나만 실제 파일시스템이 아니라 [writeThroughTemporary] 의 원시 연산 자리에서 본다 —
 * 임시 파일을 만들 수 있는 폴더는 그 파일을 지울 수도 있어 두 실패를 겹쳐 낼 수 없다.
 */
class AwtPatchFilesSpec : FunSpec({

    val files = AwtPatchFiles(openTitle = { "열기" }, saveTitle = { "저장" }, directoryTitle = { "폴더" })

    test("덮어써도 임시 파일을 남기지 않고 내용이 새 것으로 바뀐다") {
        val directory = Files.createTempDirectory("undine-patch-write")
        val target = directory.resolve("out.patch")

        files.writeAtomically(target, "첫 내용".toByteArray())
        files.writeAtomically(target, "둘째 내용".toByteArray())

        Files.readAllBytes(target).decodeToString() shouldBe "둘째 내용"
        directory.names() shouldContainExactly listOf("out.patch")
    }

    test("쓰지 못하면 기존 파일이 그대로 남고 임시 파일도 남기지 않는다") {
        val directory = Files.createTempDirectory("undine-patch-write-failure")
        val keep = directory.resolve("keep.patch")
        Files.write(keep, "원래 내용".toByteArray())
        // 비어 있지 않은 디렉터리 자리에는 파일을 옮길 수 없다 — 쓰기 실패를 실제로 만들어 낸다.
        val blocked = directory.resolve("blocked")
        Files.createDirectory(blocked)
        Files.write(blocked.resolve("child"), ByteArray(1))

        shouldThrow<IOException> { files.writeAtomically(blocked, "새 내용".toByteArray()) }

        Files.readAllBytes(keep).decodeToString() shouldBe "원래 내용"
        directory.names() shouldContainExactly listOf("blocked", "keep.patch")
    }

    test("없는 폴더에도 쓸 수 있고 지우기는 없는 파일에 대해 조용히 끝난다") {
        val directory = Files.createTempDirectory("undine-patch-write-nested")
        val target = directory.resolve("nested/out.patch")

        files.writeAtomically(target, "내용".toByteArray())
        files.exists(target) shouldBe true

        files.delete(target)
        files.exists(target) shouldBe false
        files.delete(target)
    }

    // 아래 두 경우는 실제 파일시스템으로 만들 수 없다 — 임시 파일을 만들 수 있는 폴더는 그 파일을 지울
    // 수도 있다. 그래서 원시 연산을 갈라 둔 자리에서 확인한다.
    test("쓰기 실패 뒤 임시 파일까지 지우지 못하면 남은 파일을 실패에 실어 올린다") {
        val temporary = Path.of("/tmp/undine-patch-1.tmp")

        val thrown = shouldThrow<IOException> {
            writeThroughTemporary(
                createTemporary = { temporary },
                fill = { throw IOException("디스크 가득 참") },
                moveIntoPlace = { error("쓰지 못했으면 옮기지 않는다") },
                removeTemporary = { throw IOException("권한 없음") },
            )
        }

        thrown.message shouldBe "디스크 가득 참"
        thrown.leftoverTemporaries() shouldContainExactly listOf(temporary)
    }

    test("이동 실패 뒤 임시 파일까지 지우지 못하면 남은 파일을 실패에 실어 올린다") {
        val temporary = Path.of("/tmp/undine-patch-2.tmp")

        val thrown = shouldThrow<IOException> {
            writeThroughTemporary(
                createTemporary = { temporary },
                fill = { },
                moveIntoPlace = { throw IOException("대상이 비어 있지 않은 폴더다") },
                removeTemporary = { throw IOException("권한 없음") },
            )
        }

        thrown.message shouldBe "대상이 비어 있지 않은 폴더다"
        thrown.leftoverTemporaries() shouldContainExactly listOf(temporary)
    }

    test("임시 파일을 지웠으면 남은 파일을 보고하지 않는다") {
        val removed = mutableListOf<Path>()
        val temporary = Path.of("/tmp/undine-patch-3.tmp")

        val thrown = shouldThrow<IOException> {
            writeThroughTemporary(
                createTemporary = { temporary },
                fill = { throw IOException("디스크 가득 참") },
                moveIntoPlace = { error("쓰지 못했으면 옮기지 않는다") },
                removeTemporary = { removed.add(it) },
            )
        }

        removed shouldContainExactly listOf(temporary)
        thrown.leftoverTemporaries().shouldBeEmpty()
    }
})

private fun Path.names(): List<String> =
    Files.list(this).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }
