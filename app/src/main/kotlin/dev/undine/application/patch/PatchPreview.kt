package dev.undine.application.patch

import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffLine
import dev.undine.domain.DiffLineType
import dev.undine.domain.DiffResult

/**
 * 미리보기를 시도할 패치의 상한 (1 MiB).
 *
 * `infrastructure/git/diff` 의 blob 상한과 **같은 값을 의도적으로 다시 적었다** — 그쪽 상수는
 * `internal` 이고 infrastructure 에 있어 application 이 import 하면 레이어 위반이다. 값을 맞춰 두는
 * 이유는 사용자가 diff 화면과 패치 화면에서 서로 다른 기준을 만나지 않게 하기 위해서다.
 *
 * 넘으면 [DiffResult.NotComputed] 로 알리고 **적용은 막지 않는다** — 미리보기를 못 만드는 것과
 * 적용할 수 없는 것은 다르다.
 */
const val MAX_PREVIEW_PATCH_BYTES: Int = 1024 * 1024

private const val HUNK_MARKER = "@@"
private const val GIT_BINARY_MARKER = "GIT binary patch"
private const val BINARY_FILES_MARKER = "Binary files "
private const val NO_NEWLINE_MARKER = "\\ No newline"
private const val DEFAULT_LINE_COUNT = 1

/** `@@ -oldStart,oldLineCount +newStart,newLineCount @@` 의 네 값이 들어오는 캡처 순서. */
private const val OLD_START_GROUP = 1
private const val OLD_COUNT_GROUP = 2
private const val NEW_START_GROUP = 3
private const val NEW_COUNT_GROUP = 4

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
private val HUNK_BODY_PREFIXES = setOf(' ', '+', '-')

/**
 * 패치 바이트를 도메인 hunk 로 읽는다. **`PatchGateway` 계약을 넓히지 않는다** — 게이트웨이는
 * 패치를 만들고 적용하는 계약이고, 화면이 적용 전에 읽을 형태로 푸는 것은 application 의 몫이다.
 *
 * 미리볼 수 없는 패치는 사유를 담아 [DiffResult.NotComputed] 로 돌려준다. 빈 hunk 목록으로 돌려주면
 * 화면이 "변경 없음" 으로 그려져, 무엇이 들어오는지 못 본 채 적용하게 된다.
 *
 * word-level 강조 구간은 채우지 않는다 — 그 값은 `DiffGateway` 가 두 blob 을 비교해 계산하는 것이고,
 * 패치 텍스트만으로 다시 지어내면 화면이 실제와 다른 자리를 강조한다.
 */
fun previewPatch(patch: ByteArray): DiffResult = when {
    patch.size > MAX_PREVIEW_PATCH_BYTES -> DiffResult.NotComputed(DiffResult.Reason.TOO_LARGE)
    else -> patch.toString(Charsets.UTF_8).split("\n").toPreview()
}

private fun List<String>.toPreview(): DiffResult =
    if (any(::marksBinaryContent)) DiffResult.NotComputed(DiffResult.Reason.BINARY) else DiffResult.Computed(hunks())

private fun marksBinaryContent(line: String): Boolean =
    line.startsWith(GIT_BINARY_MARKER) || line.startsWith(BINARY_FILES_MARKER)

private fun List<String>.hunks(): List<DiffHunk> {
    val hunks = mutableListOf<DiffHunk>()
    var cursor = 0
    while (cursor < size) {
        val header = HUNK_HEADER.find(this[cursor])
        val body = header?.let { subList(cursor + 1, size).takeWhile(::belongsToHunk) }
        if (header != null && body != null) hunks += header.toHunk(body)
        cursor += (body?.size ?: 0) + 1
    }
    return hunks
}

/**
 * hunk 본문에 속하는 줄인가. 다음 hunk 헤더·다음 파일 헤더에서 멈춘다.
 *
 * 빈 줄은 **문맥 줄로 본다** — git 이 문맥 줄 앞에 붙이는 공백 한 칸을 편집기가 지우는 일이 흔해,
 * 빈 줄에서 끊으면 그 아래 변경이 통째로 사라진 미리보기가 된다.
 */
private fun belongsToHunk(line: String): Boolean = when {
    line.startsWith(HUNK_MARKER) -> false
    line.startsWith(NO_NEWLINE_MARKER) -> false
    line.isEmpty() -> true
    else -> line.first() in HUNK_BODY_PREFIXES
}

private fun MatchResult.toHunk(body: List<String>): DiffHunk {
    val oldStart = groupValues[OLD_START_GROUP].toInt()
    val newStart = groupValues[NEW_START_GROUP].toInt()
    return DiffHunk(
        oldStart = oldStart,
        oldLineCount = groupValues[OLD_COUNT_GROUP].toIntOr(DEFAULT_LINE_COUNT),
        newStart = newStart,
        newLineCount = groupValues[NEW_COUNT_GROUP].toIntOr(DEFAULT_LINE_COUNT),
        lines = body.toDiffLines(oldStart, newStart),
    )
}

private fun String.toIntOr(fallback: Int): Int = if (isEmpty()) fallback else toInt()

private fun List<String>.toDiffLines(oldStart: Int, newStart: Int): List<DiffLine> {
    var oldLine = oldStart
    var newLine = newStart
    return map { raw ->
        val type = raw.lineType()
        DiffLine(
            type = type,
            content = raw.drop(1),
            oldLineNumber = if (type == DiffLineType.ADDED) null else oldLine++,
            newLineNumber = if (type == DiffLineType.DELETED) null else newLine++,
            changedRanges = emptyList(),
        )
    }
}

private fun String.lineType(): DiffLineType = when (firstOrNull()) {
    '+' -> DiffLineType.ADDED
    '-' -> DiffLineType.DELETED
    else -> DiffLineType.CONTEXT
}
