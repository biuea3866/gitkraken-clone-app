package dev.undine.infrastructure.git.diff

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

private const val DIFF_SOURCE_PATH = "src/main/kotlin/dev/undine/infrastructure/git/diff"

/** JGit 이 잡는 네이티브 핸들 — 여는 자리마다 `use {}` 가 붙어 있는지 소스로 확인한다. */
private val CLOSEABLE_FACTORIES = listOf("DiffFormatter(", "newObjectReader()", "RevWalk(", "openStream()")

class DiffResourceLifetimeSpec : FunSpec({

    val sources = File(DIFF_SOURCE_PATH).walkTopDown()
        .filter { file -> file.isFile && file.extension == "kt" }
        .toList()

    test("스캔 대상 diff 구현 소스가 실제로 존재한다") {
        sources.size shouldBeGreaterThan 0
    }

    test("diff 구현이 여는 AutoCloseable 은 모두 use 로 닫는다") {
        val violations = sources.flatMap { source ->
            source.readLines()
                .withIndex()
                .filter { (_, line) ->
                    CLOSEABLE_FACTORIES.any { factory -> line.contains(factory) } && !line.contains(".use {")
                }
                .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
        }

        violations.shouldBeEmpty()
    }
})
