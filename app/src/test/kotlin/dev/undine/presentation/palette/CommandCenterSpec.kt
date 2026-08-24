package dev.undine.presentation.palette

import androidx.compose.ui.input.key.Key
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

private const val PALETTE_SOURCE_PATH = "src/main/kotlin/dev/undine/presentation/palette"
private const val MAIN_SOURCE_PATH = "src/main/kotlin/dev/undine"

/** 팔레트 밖에서 직접 만들면 세션이 갈라지는 두 구성요소. [CommandCenter] 를 거쳐야 한다. */
private val OWNED_CONSTRUCTORS = listOf("CommandPaletteState(", "ShortcutHandler(")

/**
 * [CommandCenter] 가 "팔레트와 단축키는 같은 세션을 쓴다" 를 **구조로** 보장하는지 본다.
 *
 * 주석으로만 적힌 규약은 배선에서 깨진다 — 두 구성요소를 따로 생성하면 다른 세션을 넘겨도 컴파일되고,
 * 그러면 단축키로 실행한 명령이 팔레트 검색 우선순위에 반영되지 않는다. 그래서 동작(같은 이력)과
 * 구조(팔레트 밖에서 직접 생성하지 않음)를 함께 검증한다.
 */
class CommandCenterSpec : BehaviorSpec({

    given("CommandCenter 로 만든 팔레트와 단축키") {
        val shortcut = primaryShortcut(Key.K)
        val registry = registryOf(
            testCommand("first", title = "첫째"),
            testCommand("second", title = "둘째", shortcut = shortcut),
        )
        val center = CommandCenter(registry)

        `when`("단축키로 명령을 실행하면") {
            center.shortcutHandler.handle(shortcut)

            then("팔레트 상태 홀더가 그 실행을 최근 이력으로 읽는다") {
                center.paletteState.recentCommandIds shouldContainExactly listOf(CommandId("second"))
            }

            then("검색 결과에서 최근 실행한 명령이 앞선다") {
                center.paletteState.query = ""
                center.paletteState.candidates.first().command.id shouldBe CommandId("second")
            }
        }
    }

    given("팔레트 밖 소스") {
        `when`("두 구성요소를 직접 생성하는지 훑으면") {
            val paletteDir = File(PALETTE_SOURCE_PATH).canonicalPath
            val outsidePalette = File(MAIN_SOURCE_PATH).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.canonicalPath.startsWith(paletteDir) }
                .toList()

            then("CommandCenter 를 거치지 않고 만드는 곳이 없다") {
                // 훑을 소스가 실제로 있었는지 먼저 확인한다 — 경로가 어긋나면 이 테스트는 늘 통과한다.
                outsidePalette.isEmpty() shouldBe false
                outsidePalette.flatMap { source ->
                    source.readLines()
                        .withIndex()
                        .filter { (_, line) -> OWNED_CONSTRUCTORS.any { line.contains(it) } }
                        .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
                }.shouldBeEmpty()
            }
        }
    }
})
