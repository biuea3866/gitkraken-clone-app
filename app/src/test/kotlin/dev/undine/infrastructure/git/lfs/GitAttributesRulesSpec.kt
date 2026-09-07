package dev.undine.infrastructure.git.lfs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private const val PATTERN = "*.psd"
private const val OTHER_PATTERN = "*.png"

/**
 * `.gitattributes` 원문 보존 편집. **바이트 단위 왕복**이 이 스펙의 핵심이다 —
 * 추가한 규칙을 다시 빼면 추가 전 문자열과 한 글자도 다르지 않아야 한다.
 */
class GitAttributesRulesSpec : FunSpec({

    val rule = GitAttributesRules.ruleLine(PATTERN)

    test("규칙을 더하면 LFS 표식이 붙은 한 줄이 생긴다") {
        val added = GitAttributesRules.with("", PATTERN)

        added shouldBe "$rule\n"
        GitAttributesRules.patternsIn(added) shouldContainExactly listOf(PATTERN)
    }

    test("LFS 표식이 없는 줄은 추적 규칙으로 세지 않는다") {
        val content = "# 주석\n*.md text\n$rule\n"

        GitAttributesRules.patternsIn(content) shouldContainExactly listOf(PATTERN)
    }

    test("이미 있는 패턴을 다시 더하면 내용이 그대로다") {
        val content = "$rule\n"

        GitAttributesRules.with(content, PATTERN) shouldBe content
    }

    listOf(
        "LF 로 끝나는 파일" to "# 주석\n*.md text\n",
        "CRLF 로 끝나는 파일" to "# 주석\r\n*.md text\r\n",
        "CR 로 끝나는 파일" to "# 주석\r*.md text\r",
        "줄끝이 섞인 파일" to "# 주석\r\n*.md text\n*.txt text\r",
        "마지막 개행이 없는 파일" to "# 주석\n*.md text",
        "줄끝이 섞이고 마지막 개행이 없는 파일" to "# 주석\r\n*.md text\n*.txt text",
        "빈 파일" to "",
        "빈 줄만 있는 파일" to "\n\n",
    ).forEach { (label, original) ->
        test("$label 은 규칙을 더했다 빼면 추가 전 바이트로 돌아온다") {
            val added = GitAttributesRules.with(original, PATTERN)

            GitAttributesRules.patternsIn(added) shouldContainExactly listOf(PATTERN)
            GitAttributesRules.without(added, PATTERN) shouldBe original
        }

        test("$label 에서 규칙을 빼도 나머지 줄의 바이트는 그대로다") {
            val added = GitAttributesRules.with(original, PATTERN)
            val removed = GitAttributesRules.without(added, PATTERN)

            removed.count { character -> character == '\r' } shouldBe original.count { it == '\r' }
            removed.count { character -> character == '\n' } shouldBe original.count { it == '\n' }
        }
    }

    test("여러 규칙 중 대상 패턴 줄만 빠지고 다른 규칙은 남는다") {
        val other = GitAttributesRules.ruleLine(OTHER_PATTERN)
        val content = "# 주석\r\n$other\r\n$rule\r\n*.md text\n"

        val removed = GitAttributesRules.without(content, PATTERN)

        removed shouldBe "# 주석\r\n$other\r\n*.md text\n"
        GitAttributesRules.patternsIn(removed) shouldContainExactly listOf(OTHER_PATTERN)
    }

    test("같은 패턴이 여러 줄에 있으면 전부 빠진다") {
        val content = "$rule\n*.md text\n$rule\n"

        GitAttributesRules.without(content, PATTERN) shouldBe "*.md text\n"
    }

    test("없는 패턴을 빼면 내용이 그대로다") {
        val content = "# 주석\r\n*.md text"

        GitAttributesRules.without(content, OTHER_PATTERN) shouldBe content
    }

    test("규칙만 있던 파일에서 그 규칙을 빼면 빈 내용이 된다") {
        GitAttributesRules.without("$rule\n", PATTERN) shouldBe ""
        GitAttributesRules.without(rule, PATTERN) shouldBe ""
    }

    test("탭으로 구분된 규칙도 같은 패턴으로 인식한다") {
        val content = "$PATTERN\tfilter=lfs\tdiff=lfs\tmerge=lfs\t-text\n"

        GitAttributesRules.patternsIn(content) shouldContainExactly listOf(PATTERN)
        GitAttributesRules.without(content, PATTERN) shouldBe ""
    }

    test("빈 내용에는 추적 규칙이 없다") {
        GitAttributesRules.patternsIn("").shouldBeEmpty()
    }
})
