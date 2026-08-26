package dev.undine.presentation.submodule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

private const val SUBMODULE_SOURCE_PATH = "src/main/kotlin/dev/undine/presentation/submodule"

/** 색 리터럴 — 색은 디자인 토큰을 통해서만 얻는다 (compose-ui 5). */
private val COLOR_LITERAL_PATTERNS = listOf(
    Regex("""\bColor\("""),
    Regex("""\bColor\.[A-Za-z]"""),
    Regex("""0x[0-9a-fA-F]{6,8}"""),
)

/** 한글이 든 문자열 리터럴 — 사용자 노출 문구는 `submoduleworktree.*` StringKey 를 거쳐야 한다. */
private val KOREAN_LITERAL_PATTERN = Regex(""""[^"]*[가-힣][^"]*"""")

private fun isComment(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
}

/**
 * 패널이 문자열·색을 직접 들고 있지 않은지, 사용자가 닿는 경로가 실제로 그려지는지 소스로 확인한다.
 *
 * 이 레포는 Compose UI 테스트 런타임을 쓰지 않는다 — 상태 홀더 단위 테스트로는 "그 상태 전이를
 * 부르는 화면 요소가 있는가" 를 볼 수 없어, `SearchSourceScanSpec` 과 같은 방식으로 소스를 읽는다.
 */
class SubmoduleWorktreeSourceScanSpec : FunSpec({

    val sourceFiles = File(SUBMODULE_SOURCE_PATH).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
    val panelSource = sourceFiles.single { it.name == "SubmoduleWorktreePanel.kt" }.readText()

    test("스캔 대상 submodule 소스가 실제로 존재한다") {
        sourceFiles.size shouldBeGreaterThan 0
    }

    test("패널은 색 리터럴을 쓰지 않는다") {
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

    test("패널은 Gateway 를 직접 참조하지 않는다") {
        val gatewayReferences = sourceFiles.filter { source ->
            source.readLines()
                .filterNot(::isComment)
                .any { line -> line.startsWith("import") && line.contains("Gateway") }
        }.map { it.path }

        gatewayReferences.shouldBeEmpty()
    }

    test("두 목록은 안정 키로 LazyColumn 항목을 만든다") {
        panelSource.contains("key = { it.path }") shouldBe true
        panelSource.contains("key = { it.worktree.name }") shouldBe true
    }

    test("서브모듈 행은 네 동작을 각각 조건부로 낸다") {
        listOf(
            "SubmoduleAction.INITIALIZE in row.actions",
            "SubmoduleAction.OPEN in row.actions",
            "SubmoduleAction.COMMIT_TO_PARENT in row.actions",
            "SubmoduleAction.UPDATE_FROM_PARENT in row.actions",
        ).forEach { branch -> panelSource.contains(branch) shouldBe true }

        listOf(
            "state.initialize(row)",
            "state.requestOpen(row)",
            "state.requestCommitToParent(row)",
            "state.updateFromParent(row)",
        ).forEach { call -> panelSource.contains(call) shouldBe true }
    }

    test("worktree 행은 브랜치·경로·현재 표시와 제거·prune 동작을 낸다") {
        listOf(
            "row.worktree.path.value",
            "row.worktree.branch",
            "row.isCurrent",
            "texts.current",
            "texts.orphaned",
            "state.remove(row)",
            "state.prune(row)",
        ).forEach { fragment -> panelSource.contains(fragment) shouldBe true }
    }

    test("worktree 추가 입력이 두 칸과 제출 경로로 존재한다") {
        listOf(
            "WorktreeAddForm",
            "state::updateDraftPath",
            "state::updateDraftBranch",
            "state::submitAdd",
            "state.submitAdd()",
        ).forEach { fragment -> panelSource.contains(fragment) shouldBe true }
    }

    test("주요 동작에 Enter·Space 키보드 경로가 있다") {
        panelSource.contains("Key.Enter") shouldBe true
        panelSource.contains("Key.Spacebar") shouldBe true
    }

    test("빈 상태와 더티 경고를 각각 낸다") {
        panelSource.contains("texts.submodulesEmpty") shouldBe true
        panelSource.contains("texts.worktreesEmpty") shouldBe true
        panelSource.contains("texts.dirtyRemovalWarning(count)") shouldBe true
    }
})
