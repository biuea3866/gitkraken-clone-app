package dev.undine.infrastructure.git.patch

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.patch.FileHeader

private const val DEV_NULL_NAME = "/dev/null"
private const val OLD_NAME_PREFIX = "--- "
private const val NEW_NAME_PREFIX = "+++ "
private const val OLD_PATH_PREFIX = "a/"
private const val NEW_PATH_PREFIX = "b/"
private const val NEW_FILE_MODE = "new file mode "
private const val DELETED_FILE_MODE = "deleted file mode "
private const val INDEX_PREFIX = "index "
private const val NO_NEWLINE_PREFIX = "\\"
private const val REMOVED_PREFIX = "-"
private const val ADDED_PREFIX = "+"

private const val OLD_START = 1
private const val OLD_COUNT = 2
private const val NEW_START = 3
private const val NEW_COUNT = 4
private const val TRAILING = 5

private const val INDEX_OLD_BLOB = 1
private const val INDEX_NEW_BLOB = 2
private const val INDEX_TRAILING = 3

private val HUNK_HEADER = Regex("^@@ -(\\d+)(,\\d+)? \\+(\\d+)(,\\d+)? @@(.*)$")
private val INDEX_LINE = Regex("^index ([0-9a-fA-F]+)\\.\\.([0-9a-fA-F]+)(.*)$")

/**
 * 역방향 적용이 다룰 수 없는 변경이 **하나라도** 섞였는가.
 *
 * `any` 로 판정한다. `all` 로 하면 섞인 패치가 "순수 hunk" 로 흘러 copy 의 source 를 덮어쓴다 —
 * 되돌리기가 원본을 지우는 최악의 실패다. 확장은 별도 티켓의 몫이고, 여기서는 **거부가 정답**이다.
 */
internal fun List<FileHeader>.hasNonHunkChange(): Boolean = any { header -> header.isNotPureHunk() }

private fun FileHeader.isNotPureHunk(): Boolean =
    patchType != FileHeader.PatchType.UNIFIED ||
        changeType == DiffEntry.ChangeType.COPY ||
        changeType == DiffEntry.ChangeType.RENAME ||
        (changeType == DiffEntry.ChangeType.MODIFY && oldMode != newMode)

/**
 * 순수 hunk 패치를 거꾸로 뒤집는다. JGit 에는 역적용 API 가 없으므로 패치 자체를 뒤집어
 * **같은 격리 적용 경로**로 흘려보낸다 — 역방향 전용 적용 코드를 따로 만들지 않는다.
 *
 * 바이트를 Latin-1 로 다룬다. 이 인코딩만이 임의 바이트를 손실 없이 왕복시키므로, UTF-8 이 아닌
 * 내용이 섞여도 hunk 본문이 깨지지 않는다.
 *
 * hunk 본문과 파일 헤더는 **줄 수를 세어** 가른다. `--- ` 로 시작하는 줄은 파일 헤더일 수도 있고
 * `-- ` 를 지우는 hunk 줄일 수도 있어, 접두사만 보면 구분할 수 없다.
 */
internal fun reversePatchBytes(patch: ByteArray): ByteArray {
    val lines = patch.toString(Charsets.ISO_8859_1).split("\n")
    val reversed = mutableListOf<String>()
    var hunk = HunkRemainder.OUTSIDE
    var cursor = 0
    while (cursor < lines.size) {
        val line = lines[cursor]
        val header = if (hunk.inHunk) null else HUNK_HEADER.matchEntire(line)
        val nextLine = lines.getOrNull(cursor + 1)
        when {
            hunk.inHunk -> {
                reversed += line.reverseBodyLine()
                hunk = hunk.consume(line)
            }

            header != null -> {
                reversed += reverseHunkHeader(header)
                // 뒤집힌 패치에서는 새 쪽 길이가 옛 쪽 길이가 된다.
                hunk = HunkRemainder(
                    old = header.groupValues[NEW_COUNT].hunkLineCount(),
                    new = header.groupValues[OLD_COUNT].hunkLineCount(),
                )
            }

            line.startsWith(OLD_NAME_PREFIX) && nextLine?.startsWith(NEW_NAME_PREFIX) == true -> {
                reversed += reverseNamePair(line, nextLine)
                cursor++
            }

            else -> reversed += line.reverseHeaderLine()
        }
        cursor++
    }
    return reversed.joinToString("\n").toByteArray(Charsets.ISO_8859_1)
}

/**
 * hunk 본문에서 아직 읽지 않은 줄 수. 접두사만으로는 본문과 파일 헤더를 가를 수 없어
 * (`--- ` 는 헤더일 수도, `-- ` 를 지우는 본문 줄일 수도 있다) 헤더가 선언한 길이를 세어 가른다.
 */
private data class HunkRemainder(val old: Int, val new: Int) {

    val inHunk: Boolean get() = old > 0 || new > 0

    /** 문맥 줄은 양쪽을, 삭제·추가 줄은 한쪽만 소비한다. `\ No newline` 은 어느 쪽도 소비하지 않는다. */
    fun consume(line: String): HunkRemainder = when {
        line.startsWith(NO_NEWLINE_PREFIX) -> this
        line.startsWith(ADDED_PREFIX) -> copy(new = new - 1)
        line.startsWith(REMOVED_PREFIX) -> copy(old = old - 1)
        else -> HunkRemainder(old - 1, new - 1)
    }

    companion object {
        val OUTSIDE = HunkRemainder(0, 0)
    }
}

/** `@@ -a,b +c,d @@` 의 양쪽을 맞바꾼다. 뒤집힌 패치에서는 옛 쪽이 새 쪽이 된다. */
private fun reverseHunkHeader(hunk: MatchResult): String {
    val values = hunk.groupValues
    return "@@ -${values[NEW_START]}${values[NEW_COUNT]} " +
        "+${values[OLD_START]}${values[OLD_COUNT]} @@${values[TRAILING]}"
}

/** 줄 수를 생략한 `@@ -1 +1 @@` 은 한 줄을 뜻한다. */
private fun String.hunkLineCount(): Int = removePrefix(",").toIntOrNull() ?: 1

private fun String.reverseBodyLine(): String = when {
    startsWith(ADDED_PREFIX) -> REMOVED_PREFIX + drop(1)
    startsWith(REMOVED_PREFIX) -> ADDED_PREFIX + drop(1)
    else -> this
}

private fun String.reverseHeaderLine(): String = when {
    startsWith(NEW_FILE_MODE) -> DELETED_FILE_MODE + removePrefix(NEW_FILE_MODE)
    startsWith(DELETED_FILE_MODE) -> NEW_FILE_MODE + removePrefix(DELETED_FILE_MODE)
    startsWith(INDEX_PREFIX) -> INDEX_LINE.matchEntire(this)?.let { match ->
        "$INDEX_PREFIX${match.groupValues[INDEX_NEW_BLOB]}..${match.groupValues[INDEX_OLD_BLOB]}" +
            match.groupValues[INDEX_TRAILING]
    } ?: this

    else -> this
}

/**
 * `--- a/x` · `+++ b/x` 쌍을 맞바꾼다. 접두사(`a/`·`b/`)는 자리에 맞게 다시 붙인다 —
 * 그대로 바꿔치기하면 `--- b/x` 가 되어 패치 관례에서 벗어난다.
 */
private fun reverseNamePair(oldLine: String, newLine: String): List<String> {
    val oldName = oldLine.removePrefix(OLD_NAME_PREFIX).substringBefore('\t')
    val newName = newLine.removePrefix(NEW_NAME_PREFIX).substringBefore('\t')
    return listOf(
        OLD_NAME_PREFIX + newName.withSidePrefix(OLD_PATH_PREFIX),
        NEW_NAME_PREFIX + oldName.withSidePrefix(NEW_PATH_PREFIX),
    )
}

private fun String.withSidePrefix(prefix: String): String =
    if (this == DEV_NULL_NAME) this else prefix + substringAfter('/', this)
