package dev.undine.presentation.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

private const val SEARCH_SOURCE_PATH = "src/main/kotlin/dev/undine/presentation/search"

/** 색 리터럴 — 색은 디자인 토큰을 통해서만 얻는다 (compose-ui 5). */
private val COLOR_LITERAL_PATTERNS = listOf(
    Regex("""\bColor\("""),
    Regex("""\bColor\.[A-Za-z]"""),
    Regex("""0x[0-9a-fA-F]{6,8}"""),
)

/** 한글이 든 문자열 리터럴 — 사용자 노출 문구는 `search.*` StringKey 를 거쳐야 한다. */
private val KOREAN_LITERAL_PATTERN = Regex(""""[^"]*[가-힣][^"]*"""")

private fun isComment(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
}

/**
 * 검색 화면이 문자열·색을 직접 들고 있지 않은지 소스로 확인한다.
 *
 * 렌더링 테스트로는 "이 문구가 어디서 왔는지" 를 못 본다 — 하드코딩된 한글도 화면에는 똑같이 보인다.
 * 그래서 `DesignSourceScanSpec` 과 같은 방식으로 소스를 읽는다.
 */
class SearchSourceScanSpec : FunSpec({

    val sourceFiles = File(SEARCH_SOURCE_PATH).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    test("스캔 대상 search 소스가 실제로 존재한다") {
        sourceFiles.size shouldBeGreaterThan 0
    }

    test("검색 화면은 색 리터럴을 쓰지 않는다") {
        val violations = sourceFiles.flatMap { source ->
            source.readLines()
                .withIndex()
                .filter { (_, line) -> COLOR_LITERAL_PATTERNS.any { it.containsMatchIn(line) } }
                .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
        }

        violations.shouldBeEmpty()
    }

    test("사용자 노출 문구를 소스에 하드코딩하지 않는다") {
        val violations = sourceFiles.flatMap { source ->
            source.readLines()
                .withIndex()
                .filterNot { (_, line) -> isComment(line) }
                .filter { (_, line) -> KOREAN_LITERAL_PATTERN.containsMatchIn(line) }
                .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
        }

        violations.shouldBeEmpty()
    }

    test("결과 목록은 커밋 해시를 LazyColumn key 로 준다") {
        val panelSource = sourceFiles.single { it.name == "SearchPanel.kt" }.readText()

        panelSource.contains("LazyColumn") shouldBe true
        panelSource.contains("key = { _, commit -> commit.id.value }") shouldBe true
    }

    test("검색 화면은 Gateway 를 직접 참조하지 않는다") {
        val gatewayReferences = sourceFiles.filter { source ->
            source.readLines()
                .filterNot(::isComment)
                .any { line -> line.startsWith("import") && line.contains("Gateway") }
        }.map { it.path }

        gatewayReferences.shouldBeEmpty()
    }
})
