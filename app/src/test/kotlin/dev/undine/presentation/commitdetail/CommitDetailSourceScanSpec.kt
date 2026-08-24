package dev.undine.presentation.commitdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

private const val PANEL_SOURCE_PATH = "src/main/kotlin/dev/undine/presentation/commitdetail"
private const val USE_CASE_SOURCE_PATH = "src/main/kotlin/dev/undine/application/commitdetail"

/** design 소스 스캔과 같은 기준 — 색은 [dev.undine.presentation.design.ColorTokens] 밖에서 만들지 않는다. */
private val COLOR_LITERAL_PATTERNS = listOf(
    Regex("""\bColor\("""),
    Regex("""\bColor\.[A-Za-z]"""),
    Regex("""0x[0-9a-fA-F]{6,8}"""),
)

/** 화면에 보일 법한 문자열 리터럴 — 한글이 들어간 리터럴은 i18n 을 건너뛴 하드코딩이다. */
private val LOCALIZED_LITERAL_PATTERN = Regex("\"[^\"\\n]*[가-힣][^\"\\n]*\"")

private fun sourcesIn(path: String): List<File> =
    File(path).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

/**
 * 렌더링 없이 확인 가능한 축을 소스 스캔으로 강제한다 — 색·문자열 하드코딩 금지(compose-ui 규칙 5),
 * hunk 미요청, `LazyColumn` 안정 key.
 */
class CommitDetailSourceScanSpec : FunSpec({

    val panelSources = sourcesIn(PANEL_SOURCE_PATH)
    val useCaseSources = sourcesIn(USE_CASE_SOURCE_PATH)

    test("스캔 대상 소스가 실제로 존재한다") {
        panelSources.size shouldBeGreaterThan 0
        useCaseSources.size shouldBeGreaterThan 0
    }

    test("상세 패널 소스는 색 리터럴을 쓰지 않는다") {
        val violations = panelSources.flatMap { source ->
            source.readLines()
                .withIndex()
                .filter { (_, line) -> COLOR_LITERAL_PATTERNS.any { it.containsMatchIn(line) } }
                .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
        }

        violations.shouldBeEmpty()
    }

    test("상세 패널 소스는 표시 문자열을 하드코딩하지 않는다") {
        val violations = panelSources.flatMap { source ->
            source.readLines()
                .withIndex()
                .filter { (_, line) -> LOCALIZED_LITERAL_PATTERN.containsMatchIn(line) }
                .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
        }

        violations.shouldBeEmpty()
    }

    test("상세 패널 경로 어디에도 hunk 요청이 없다") {
        val violations = (panelSources + useCaseSources)
            .filter { it.readText().contains("hunksOf") }
            .map { it.path }

        violations.shouldBeEmpty()
    }

    test("텍스트를 그리는 소스는 타이포그래피 토큰을 거친다") {
        val textSources = panelSources.filter { it.readText().contains("BasicText") }
        textSources.size shouldBeGreaterThan 0

        textSources.filterNot { it.readText().contains("UndineTokens.typography") }
            .map { it.path }
            .shouldBeEmpty()
    }

    test("변경 파일 목록은 안정적 key 를 가진 LazyColumn 으로 그린다") {
        val listSource = panelSources.single { it.name == "ChangedFileList.kt" }.readText()

        listSource.contains("LazyColumn") shouldBe true
        listSource.contains("key = { file -> file.path }") shouldBe true
    }

    test("상세 패널은 Gateway 를 직접 주입받지 않고 UseCase 만 쓴다") {
        panelSources.filter { it.readText().contains("dev.undine.domain.DiffGateway") }
            .map { it.path }
            .shouldBeEmpty()
    }
})
