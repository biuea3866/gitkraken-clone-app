package dev.undine.application.patch

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.revwalk.RevCommit
import java.io.ByteArrayOutputStream
import java.io.File

/** 한글 `한` = UTF-8 `ED 95 9C` — git 은 이 세 바이트를 8진 이스케이프로 적는다. */
private const val KOREAN_QUOTED = "\"a/\\355\\225\\234.txt\" \"b/\\355\\225\\234.txt\""

/**
 * 생성 전 포함 파일 목록의 경로 파싱.
 *
 * **인용 형식은 JGit 이 정한다** — 우리가 상상한 형식을 우리가 다시 읽는 테스트는 아무것도 증명하지
 * 않는다. 마지막 테스트가 실제 임시 저장소에서 나온 패치로 그 가정을 못 박고, 나머지는 그 형식의
 * 갈래(인용 한쪽만·이스케이프 종류·해석 불가)를 따로 짚는다.
 */
class PatchPathsSpec : FunSpec({

    test("인용되지 않은 흔한 경로는 그대로 읽는다") {
        paths("diff --git a/src/Main.kt b/src/Main.kt") shouldContainExactly listOf("src/Main.kt")
    }

    test("rename 은 목적지 경로를 읽는다 — 사용자가 결과 저장소에서 보게 될 이름이 그쪽이다") {
        paths("diff --git a/old.txt b/new.txt") shouldContainExactly listOf("new.txt")
    }

    test("인용된 한글 경로가 목록에서 빠지지 않는다") {
        paths("diff --git $KOREAN_QUOTED") shouldContainExactly listOf("한.txt")
    }

    // 인용 여부는 경로마다 따로 정해진다 — 한쪽 규칙만 보면 rename 패치에서 반쪽이 빠진다.
    test("rename 은 양쪽 인용이 서로 달라도 목적지를 읽는다") {
        paths("diff --git a/old.txt \"b/\\355\\225\\234.txt\"") shouldContainExactly listOf("한.txt")
        paths("diff --git \"a/\\355\\225\\234.txt\" b/new.txt") shouldContainExactly listOf("new.txt")
    }

    test("탭·따옴표·역슬래시가 든 경로의 이스케이프를 되돌린다") {
        paths("diff --git \"a/ta\\tb.txt\" \"b/ta\\tb.txt\"") shouldContainExactly listOf("ta\tb.txt")
        paths("diff --git \"a/q\\\"t.txt\" \"b/q\\\"t.txt\"") shouldContainExactly listOf("q\"t.txt")
        paths("diff --git \"a/b\\\\s.txt\" \"b/b\\\\s.txt\"") shouldContainExactly listOf("b\\s.txt")
    }

    // 인용되지 않은 경로에도 공백은 들어간다 — 자를 자리를 잘못 고르면 이름이 반토막 난다.
    test("공백이 든 인용되지 않은 경로를 반토막 내지 않는다") {
        paths("diff --git a/my file.txt b/my file.txt") shouldContainExactly listOf("my file.txt")
    }

    // 목록에서 조용히 빠지는 것이 이 결함의 본질이었다. 못 읽었으면 못 읽었다는 사실이 남아야 한다.
    test("인용을 풀 수 없는 헤더는 건너뛰지 않고 원문을 남긴다") {
        paths("diff --git \"a/끊긴 b/x.txt") shouldContainExactly listOf("\"a/끊긴 b/x.txt")
    }

    test("실제 저장소가 만든 패치의 인용 경로를 그대로 읽는다") {
        Git.init().setDirectory(tempdir()).setInitialBranch("main").call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Undine Tester")
                setString("user", null, "email", "tester@undine.dev")
                save()
            }
            val base = git.commitWith(mapOf("base.txt" to "b\n"))
            val tricky = git.commitWith(
                mapOf(
                    "한글 경로.txt" to "k\n",
                    "quo\"te.txt" to "q\n",
                    "back\\slash.txt" to "s\n",
                    "plain.txt" to "p\n",
                ),
            )

            patchPathsOf(git.diffBytes(base, tricky)) shouldContainExactlyInAnyOrder listOf(
                "한글 경로.txt",
                "quo\"te.txt",
                "back\\slash.txt",
                "plain.txt",
            )
        }
    }
})

private fun paths(header: String): List<String> = patchPathsOf("$header\n".toByteArray())

private fun Git.commitWith(files: Map<String, String>): RevCommit {
    files.forEach { (path, content) -> File(repository.workTree, path).writeText(content) }
    add().addFilepattern(".").call()
    return commit().setMessage("변경").call()
}

private fun Git.diffBytes(from: RevCommit, to: RevCommit): ByteArray {
    val out = ByteArrayOutputStream()
    DiffFormatter(out).use { formatter ->
        formatter.setRepository(repository)
        formatter.format(from.tree, to.tree)
    }
    return out.toByteArray()
}
