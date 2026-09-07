package dev.undine.infrastructure.git.lfs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.engine.spec.tempdir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

private const val USER_CONTENT = "# 사용자 규칙\n*.md text\n"
private const val REPLACEMENT = "*.psd filter=lfs\n"

/**
 * `.gitattributes` **쓰기 안전성**. 이 스펙의 축은 하나다 —
 * 쓰지 못할 상황에서는 원본 바이트가 한 글자도 바뀌지 않는다.
 *
 * 실패하고 원본을 남기는 쪽이 성공한 척하고 반쯤 덮는 쪽보다 항상 낫다. 사용자가 잃은 규칙은
 * 되돌릴 수 없지만, 실패한 편집은 다시 하면 된다.
 */
class GitAttributesFileSpec : FunSpec({

    test("읽은 뒤 파일이 바뀌면 옛 스냅샷으로 덮어쓰지 않고 실패한다") {
        val work = tempdir().toPath()
        write(work, USER_CONTENT)
        val read = GitAttributesFile.readIn(work)
        // 그 사이 사용자가 (또는 다른 도구가) 규칙을 하나 더 넣었다.
        val changed = USER_CONTENT + "*.txt text\n"
        write(work, changed)

        shouldThrow<IOException> { GitAttributesFile.writeIn(work, read, REPLACEMENT) }

        read(work) shouldBe changed
    }

    test("심볼릭 링크는 링크도 링크 대상도 건드리지 않고 거부한다") {
        val work = tempdir().toPath()
        val outside = tempdir().toPath().resolve("남의-파일")
        Files.write(outside, USER_CONTENT.toByteArray(StandardCharsets.UTF_8))
        Files.createSymbolicLink(GitAttributesFile.pathIn(work), outside)

        shouldThrow<IOException> { GitAttributesFile.readIn(work) }
        shouldThrow<IOException> {
            GitAttributesFile.writeIn(work, AttributesContent.Present(USER_CONTENT), REPLACEMENT)
        }

        String(Files.readAllBytes(outside), StandardCharsets.UTF_8) shouldBe USER_CONTENT
        Files.isSymbolicLink(GitAttributesFile.pathIn(work)) shouldBe true
    }

    test("교체해도 기존 파일 권한이 그대로다") {
        val work = tempdir().toPath()
        write(work, USER_CONTENT)
        val file = GitAttributesFile.pathIn(work)
        if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null) {
            val permissions = PosixFilePermissions.fromString("rw-rw-r--")
            Files.setPosixFilePermissions(file, permissions)

            GitAttributesFile.writeIn(work, GitAttributesFile.readIn(work), REPLACEMENT)

            // 원자 교체가 임시 파일의 소유자 전용 권한을 끌고 들어오면 안 된다.
            Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) shouldBe permissions
        }
    }

    test("쓰기에 성공하면 임시 파일을 남기지 않는다") {
        val work = tempdir().toPath()
        write(work, USER_CONTENT)

        GitAttributesFile.writeIn(work, GitAttributesFile.readIn(work), REPLACEMENT)

        read(work) shouldBe REPLACEMENT
        leftoverTemporaries(work).shouldBeEmpty()
    }

    test("쓰기를 거부해도 임시 파일을 남기지 않는다") {
        val work = tempdir().toPath()
        write(work, USER_CONTENT)

        shouldThrow<IOException> { GitAttributesFile.writeIn(work, AttributesContent.Absent, REPLACEMENT) }

        read(work) shouldBe USER_CONTENT
        leftoverTemporaries(work).shouldBeEmpty()
    }

    test("원자 교체를 지원하지 않으면 덮어쓰지 않고 원본을 그대로 남긴다") {
        val work = tempdir().toPath()
        write(work, USER_CONTENT)
        val staged = mutableListOf<String>()
        // 임시 파일을 다 쓴 **뒤** 교체 순간에만 실패한다 — 그 앞에서 끝나면 이 경로가 돌지 않는다.
        val unsupported = AttributesFileMove { source, _ ->
            staged += String(Files.readAllBytes(source), StandardCharsets.UTF_8)
            throw AtomicMoveNotSupportedException(source.toString(), null, "테스트 파일시스템")
        }

        shouldThrow<IOException> {
            GitAttributesFile.writeIn(work, GitAttributesFile.readIn(work), REPLACEMENT, unsupported)
        }

        // 임시 파일에는 새 내용이 실렸으나, 원본은 한 글자도 바뀌지 않았고 잔재도 없다.
        staged shouldBe listOf(REPLACEMENT)
        read(work) shouldBe USER_CONTENT
        leftoverTemporaries(work).shouldBeEmpty()
    }

    test("부재와 0바이트 파일은 끝까지 다른 값이다") {
        val absent = tempdir().toPath()
        val empty = tempdir().toPath()
        write(empty, "")

        GitAttributesFile.readIn(absent) shouldBe AttributesContent.Absent
        GitAttributesFile.readIn(empty) shouldBe AttributesContent.Present("")
    }
})

private fun write(workingDirectory: Path, content: String) {
    Files.write(GitAttributesFile.pathIn(workingDirectory), content.toByteArray(StandardCharsets.UTF_8))
}

private fun read(workingDirectory: Path): String =
    String(Files.readAllBytes(GitAttributesFile.pathIn(workingDirectory)), StandardCharsets.UTF_8)

private fun leftoverTemporaries(workingDirectory: Path): List<Path> =
    Files.list(workingDirectory).use { entries ->
        entries.filter { entry -> entry.fileName.toString() != GIT_ATTRIBUTES }.toList()
    }
