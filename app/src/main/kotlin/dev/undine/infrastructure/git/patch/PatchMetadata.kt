package dev.undine.infrastructure.git.patch

import dev.undine.domain.Person
import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyMode

/** `git format-patch` 가 패치 앞머리에 남긴 작성자·메시지. 일반 diff 에는 없다. */
internal data class PatchMetadata(val author: Person?, val message: String?)

/** 커밋 생성에 실제로 쓸 값. 패치와 인자 중 어느 쪽에서 왔든 여기서는 이미 확정돼 있다. */
internal data class CommitRequest(val author: Person, val message: String)

private const val SUBJECT_HEADER = "Subject:"
private const val FROM_HEADER = "From:"
private const val BODY_TERMINATOR = "---"
private const val MBOX_SEPARATOR = "From "
private val HEADER_LINE = Regex("^[A-Za-z][A-Za-z-]*: ?.*$")
private val AUTHOR_LINE = Regex("^\\s*(.*?)\\s*<(.+?)>\\s*$")
private val SUBJECT_TAG = Regex("^\\s*(\\[[^]]*]\\s*)+")

/**
 * 커밋에 쓸 작성자·메시지를 정한다. **패치에 담긴 메타데이터가 인자보다 우선**이다 —
 * 패치를 만든 사람의 이름을 적용하는 사람의 이름으로 바꿔 쓰지 않는다.
 *
 * 어느 쪽에도 없으면 **거부**한다. 작성자와 메시지를 지어내지 않는다.
 */
internal fun resolveCommitRequest(patch: ByteArray, mode: ApplyMode.CreateCommit): CommitRequest {
    val metadata = parsePatchMetadata(patch)
    val author = metadata.author ?: mode.author
        ?: throw UndineException.StateViolation("패치에도 인자에도 작성자가 없어 커밋을 만들 수 없습니다")
    val message = metadata.message?.takeIf(String::isNotBlank) ?: mode.message?.takeIf(String::isNotBlank)
        ?: throw UndineException.StateViolation("패치에도 인자에도 커밋 메시지가 없어 커밋을 만들 수 없습니다")
    return CommitRequest(author, message)
}

/**
 * 패치 앞머리의 메일 헤더를 읽는다. 첫 비-헤더 줄에서 멈추므로 일반 diff 는 빈 결과가 된다.
 *
 * **UTF-8 로 읽는다.** 여기서 뽑는 것은 사람이 읽을 작성자 이름과 커밋 메시지이므로, 바이트를
 * 보존하는 Latin-1 로 읽으면 한글 제목이 깨진 채 이력에 박힌다 (바이트 보존이 필요한 곳은
 * 패치를 다시 써 내보내는 [reversePatchBytes] 쪽이고, 거기는 Latin-1 을 쓴다).
 */
internal fun parsePatchMetadata(patch: ByteArray): PatchMetadata {
    val lines = patch.toString(Charsets.UTF_8).split("\n")
    val headers = mutableListOf<String>()
    // `format-patch` 는 mbox 구분자 `From <sha> <date>` 로 시작한다. 콜론이 없어 헤더로 읽히지 않으므로
    // 여기서 건너뛴다 — 그러지 않으면 첫 줄에서 멈춰 그 아래 `From:`·`Subject:` 를 통째로 놓친다.
    var cursor = if (lines.firstOrNull()?.startsWith(MBOX_SEPARATOR) == true) 1 else 0
    while (cursor < lines.size) {
        val line = lines[cursor]
        val isContinuation = headers.isNotEmpty() && line.isNotEmpty() && line[0].isWhitespace()
        when {
            isContinuation -> headers[headers.lastIndex] = headers.last() + " " + line.trim()
            HEADER_LINE.matches(line) -> headers += line
            else -> break
        }
        cursor++
    }
    val subject = headers.lastOrNull { header -> header.startsWith(SUBJECT_HEADER, ignoreCase = true) }
        ?.removeRange(0, SUBJECT_HEADER.length)?.trim()?.replace(SUBJECT_TAG, "")?.trim()
    val from = headers.lastOrNull { header -> header.startsWith(FROM_HEADER, ignoreCase = true) }
        ?.removeRange(0, FROM_HEADER.length)?.trim()
    return PatchMetadata(from?.toPerson(), subject?.withBody(lines, cursor))
}

private fun String.toPerson(): Person? =
    AUTHOR_LINE.matchEntire(this)?.let { match ->
        Person(name = match.groupValues[1].trim('"', ' '), email = match.groupValues[2].trim())
    }

/**
 * `format-patch` 는 제목 다음 빈 줄 뒤에 본문을 두고 `---` 로 끝낸다. 본문이 있으면 함께 담는다 —
 * 제목만 쓰면 패치 작성자가 남긴 설명이 조용히 사라진다.
 */
private fun String.withBody(lines: List<String>, headerEnd: Int): String {
    val body = lines.drop(headerEnd)
        .dropWhile(String::isBlank)
        .takeWhile { line -> line.trimEnd() != BODY_TERMINATOR }
        .joinToString("\n")
        .trim()
    return if (body.isEmpty()) this else "$this\n\n$body"
}
