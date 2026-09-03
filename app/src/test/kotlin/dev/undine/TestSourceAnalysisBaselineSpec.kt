package dev.undine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

private const val BUILD_SCRIPT_PATH = "build.gradle.kts"
private const val TEST_BASELINE_PATH = "../config/detekt/detekt-baseline-test.xml"
private const val DETEKT_README_PATH = "../config/detekt/README.md"

private val BASELINE_ENTRY = Regex("""<ID>([^<]+)</ID>""")
private val DOCUMENTED_ENTRY_COUNT = Regex("""baseline 항목 수: (\d+)""")

/**
 * 테스트 소스에도 정적 분석이 실제로 걸려 있는지, 그리고 baseline 이 **빚 목록**으로 남아
 * 있는지 본다.
 *
 * `detektTest` 가 `check` 에서 빠지면 테스트 소스는 조용히 검사 밖으로 나간다 — 검사를 안 도는
 * 코드가 검사의 근거가 된다. baseline 도 항목 수를 적어 두지 않으면 새 위반을 등록해 통과시켜도
 * 아무도 알아채지 못한다. 두 어긋남을 여기서 잡는다.
 */
class TestSourceAnalysisBaselineSpec : FunSpec({

    val baselineText = { File(TEST_BASELINE_PATH).readText() }

    test("detektTest 가 check 검증 경로에 걸려 있다") {
        val wiring = File(BUILD_SCRIPT_PATH).readLines()
            .map { line -> line.trim() }
            .filterNot { line -> line.startsWith("//") || line.startsWith("*") }
            .filter { line -> line.contains("detektTest") && line.contains("dependsOn") }

        wiring.size shouldBeGreaterThan 0
    }

    test("테스트 소스 baseline 파일이 존재하고 기존 위반을 담고 있다") {
        File(TEST_BASELINE_PATH).isFile shouldBe true
        BASELINE_ENTRY.findAll(baselineText()).count() shouldBeGreaterThan 0
    }

    test("문서에 적힌 baseline 항목 수가 실제 항목 수와 일치한다") {
        val documented = DOCUMENTED_ENTRY_COUNT.find(File(DETEKT_README_PATH).readText())
            ?.groupValues
            ?.get(1)
            ?.toInt()

        documented shouldBe BASELINE_ENTRY.findAll(baselineText()).count()
    }
})
