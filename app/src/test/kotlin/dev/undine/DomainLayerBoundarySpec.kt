package dev.undine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

private const val DOMAIN_SOURCE_PATH = "src/main/kotlin/dev/undine/domain"
private val FORBIDDEN_LAYERS = listOf("application", "infrastructure", "presentation")

/**
 * domain 은 프레임워크(JGit·Compose·코루틴)를 자유롭게 쓸 수 있지만
 * 다른 레이어를 import 하면 p1 이다 — 소스를 직접 스캔해 역방향 import 를 막는다.
 */
class DomainLayerBoundarySpec : FunSpec({

    val domainSourceDirectory = File(DOMAIN_SOURCE_PATH)
    val domainSourceFiles = domainSourceDirectory.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    test("스캔 대상 domain 소스가 실제로 존재한다") {
        domainSourceDirectory.isDirectory shouldBe true
        domainSourceFiles.size shouldBeGreaterThan 0
    }

    test("domain 소스는 application·infrastructure·presentation 을 import 하지 않는다") {
        val violations = domainSourceFiles.flatMap { source ->
            source.readLines()
                .withIndex()
                .filter { (_, line) ->
                    val trimmed = line.trim()
                    trimmed.startsWith("import ") &&
                        FORBIDDEN_LAYERS.any { layer -> trimmed.contains("dev.undine.$layer") }
                }
                .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
        }

        violations.shouldBeEmpty()
    }
})
