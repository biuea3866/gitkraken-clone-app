package dev.undine.application.patch

import dev.undine.domain.Person

/**
 * `git format-patch` 가 패치 앞머리에 남긴 작성자·메시지. 일반 diff 에는 없으므로 둘 다 `null` 이다.
 *
 * [isEmpty] 가 참이면 커밋 모드 적용에 쓸 값이 패치에 **없다** — 화면은 그때만 입력을 요구한다.
 */
data class PatchCommitMetadata(val author: Person?, val message: String?) {

    val isEmpty: Boolean get() = author == null && message == null
}

private const val SUBJECT_HEADER = "Subject:"
private const val FROM_HEADER = "From:"
private const val BODY_TERMINATOR = "---"
private const val MBOX_SEPARATOR = "From "
private const val NAME_GROUP = 1
private const val EMAIL_GROUP = 2

private val HEADER_LINE = Regex("^[A-Za-z][A-Za-z-]*: ?.*$")
private val AUTHOR_LINE = Regex("""^\s*(.*?)\s*<(.+?)>\s*$""")
private val SUBJECT_TAG = Regex("""^\s*(\[[^]]*]\s*)+""")

/**
 * 커밋 모드 화면이 "패치에서 가져옴" 으로 보여 줄 값을 읽는다.
 *
 * 게이트웨이도 적용 시점에 같은 헤더를 읽어 인자보다 우선한다. 여기서 다시 읽는 이유는 **누르기 전에**
 * 무엇이 쓰일지 보여 주기 위해서다 — 화면이 입력을 요구했는데 적용 결과에 패치의 작성자가 박히면
 * 사용자는 자기가 적은 값이 어디로 갔는지 알 수 없다.
 *
 * UTF-8 로 읽는다. 여기서 뽑는 것은 사람이 읽을 이름과 메시지이므로, 바이트를 보존하는 Latin-1 로
 * 읽으면 한글 제목이 깨진 채 화면에 나온다.
 */
fun patchCommitMetadataOf(patch: ByteArray): PatchCommitMetadata {
    val lines = patch.toString(Charsets.UTF_8).split("\n")
    val headers = mutableListOf<String>()
    // `format-patch` 의 mbox 구분자 `From <sha> <date>` 에는 콜론이 없어 헤더로 읽히지 않는다 —
    // 건너뛰지 않으면 첫 줄에서 멈춰 그 아래 `From:`·`Subject:` 를 통째로 놓친다.
    var cursor = if (lines.firstOrNull()?.startsWith(MBOX_SEPARATOR) == true) 1 else 0
    while (cursor < lines.size) {
        val line = lines[cursor]
        val isContinuation = headers.isNotEmpty() && line.isNotEmpty() && line.first().isWhitespace()
        when {
            isContinuation -> headers[headers.lastIndex] = headers.last() + " " + line.trim()
            HEADER_LINE.matches(line) -> headers += line
            else -> break
        }
        cursor++
    }
    val subject = headers.lastValueOf(SUBJECT_HEADER)?.replace(SUBJECT_TAG, "")?.trim()
    return PatchCommitMetadata(
        author = headers.lastValueOf(FROM_HEADER)?.toPerson(),
        message = subject?.withBody(lines, cursor),
    )
}

private fun List<String>.lastValueOf(header: String): String? =
    lastOrNull { line -> line.startsWith(header, ignoreCase = true) }
        ?.removeRange(0, header.length)
        ?.trim()

private fun String.toPerson(): Person? =
    AUTHOR_LINE.matchEntire(this)?.let { match ->
        Person(name = match.groupValues[NAME_GROUP].trim('"', ' '), email = match.groupValues[EMAIL_GROUP].trim())
    }

/** `format-patch` 는 제목 다음 빈 줄 뒤에 본문을 두고 `---` 로 끝낸다. 본문이 있으면 함께 담는다. */
private fun String.withBody(lines: List<String>, headerEnd: Int): String {
    val body = lines.drop(headerEnd)
        .dropWhile(String::isBlank)
        .takeWhile { line -> line.trimEnd() != BODY_TERMINATOR }
        .joinToString("\n")
        .trim()
    return if (body.isEmpty()) this else "$this\n\n$body"
}
