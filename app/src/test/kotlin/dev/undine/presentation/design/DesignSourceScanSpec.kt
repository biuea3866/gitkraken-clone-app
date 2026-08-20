package dev.undine.presentation.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

private const val DESIGN_SOURCE_PATH = "src/main/kotlin/dev/undine/presentation/design"

/** 색 리터럴이 허용되는 유일한 파일 — 라이트/다크 전환이 여기 한 곳에서 끝나야 한다. */
private const val COLOR_LITERAL_OWNER = "ColorTokens.kt"

private val COLOR_LITERAL_PATTERNS = listOf(
    Regex("""\bColor\("""),
    Regex("""\bColor\.[A-Za-z]"""),
    Regex("""0x[0-9a-fA-F]{6,8}"""),
)

/**
 * compose-ui 규칙 5(테마 값 하드코딩 금지)를 소스 스캔으로 강제한다.
 * Compose UI 테스트 의존성 없이 검증 가능한 축이라 렌더링 없이 소스를 읽는다.
 */
class DesignSourceScanSpec : FunSpec({

    val designSourceDirectory = File(DESIGN_SOURCE_PATH)
    val designSourceFiles = designSourceDirectory.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    test("스캔 대상 design 소스가 실제로 존재한다") {
        designSourceDirectory.isDirectory shouldBe true
        designSourceFiles.size shouldBeGreaterThan 0
        designSourceFiles.count { it.name == COLOR_LITERAL_OWNER } shouldBe 1
    }

    test("ColorTokens.kt 외의 design 소스는 색 리터럴을 쓰지 않는다") {
        val violations = designSourceFiles
            .filterNot { it.name == COLOR_LITERAL_OWNER }
            .flatMap { source ->
                source.readLines()
                    .withIndex()
                    .filter { (_, line) -> COLOR_LITERAL_PATTERNS.any { it.containsMatchIn(line) } }
                    .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
            }

        violations.shouldBeEmpty()
    }

    test("공통 컴포넌트는 디자인 토큰 타입을 거쳐서만 색을 얻는다") {
        val componentFiles = designSourceFiles.filter { it.path.contains("component") }
        componentFiles.size shouldBeGreaterThan 0

        val withoutTokenAccess = componentFiles
            .filterNot { source ->
                val text = source.readText()
                text.contains("UndineTokens") || text.contains("ColorTokens")
            }
            .map { it.path }

        withoutTokenAccess.shouldBeEmpty()
    }

    test("고정폭 서체 토큰 mono 가 타이포그래피 토큰에 정의돼 있다") {
        val typographySource = designSourceFiles.single { it.name == "TypographyTokens.kt" }.readText()
        typographySource.contains("val mono") shouldBe true
    }
})
