package dev.undine.presentation.a11y

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

private const val PRESENTATION_SOURCE_PATH = "src/main/kotlin/dev/undine/presentation"

/** 입력창을 만드는 호출 — 형태가 늘어나도 걸리게 세 이름을 모두 본다. */
private val TEXT_FIELD_CALLS = Regex("""\b(BasicTextField|OutlinedTextField|TextField)\s*\(""")

/** 호출 한 건이 이름을 갖췄다고 볼 근거. */
private val LABEL_MARKERS = listOf("contentDescription", "semantics")

/**
 * 입력창의 호출 지점마다 스크린리더가 읽을 이름이 붙어 있는지 소스로 확인한다.
 *
 * [OperableNodeAuditSpec] 의 시맨틱스 훑기가 1차 방어선이지만, 그것은 **테스트가 도달한 화면 상태**
 * 까지만 본다 — 대화상자 안이나 특정 조건에서만 나타나는 입력창은 지나친다. 그리고 입력창은
 * 다른 조작 대상과 다르게 **내용으로 이름을 대신할 수 없다**: 값은 사용자 데이터라 빈 상태에서
 * 읽을 것이 없고, 안내 문구(placeholder)는 입력이 들어오면 사라진다. 이름의 출처가 구조적으로
 * 정해져 있으므로 소스에서 확인할 수 있다.
 */
class TextFieldLabelScanSpec : FunSpec({

    val sources = File(PRESENTATION_SOURCE_PATH).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    test("스캔 대상 presentation 소스와 입력창 호출이 실제로 존재한다") {
        sources.size shouldBeGreaterThan 0
        sources.count { TEXT_FIELD_CALLS.containsMatchIn(it.readText()) } shouldBeGreaterThan 0
    }

    test("입력창을 만드는 컴포저블은 모두 이름을 붙인다") {
        val missing = sources
            .filter { TEXT_FIELD_CALLS.containsMatchIn(it.readText()) }
            .flatMap { source -> unlabeledFieldsIn(source) }

        missing.shouldBeEmpty()
    }
})

/**
 * 파일 안의 입력창 호출 중 이름이 없는 것.
 *
 * 호출부터 그 호출을 감싼 컴포저블의 끝까지를 한 덩어리로 보고 그 안에서 이름의 근거를 찾는다 —
 * `Modifier` 체인이 호출 위에 조립돼 있을 수도, 아래에 이어져 있을 수도 있다.
 */
private fun unlabeledFieldsIn(source: File): List<String> {
    val text = source.readText()
    return TEXT_FIELD_CALLS.findAll(text)
        .filterNot { match ->
            val block = enclosingBlockOf(text, match.range.first)
            LABEL_MARKERS.any { marker -> block.contains(marker) }
        }
        .map { match -> "${source.path}:${text.take(match.range.first).count { it == '\n' } + 1}" }
        .toList()
}

/** 그 호출을 담은 함수 본문. `@Composable` 경계로 자른다 — 옆 함수의 이름을 근거로 세지 않는다. */
private fun enclosingBlockOf(text: String, offset: Int): String {
    val start = text.lastIndexOf("@Composable", offset).takeIf { it >= 0 } ?: 0
    val nextComposable = text.indexOf("@Composable", offset).takeIf { it >= 0 } ?: text.length
    return text.substring(start, nextComposable)
}
