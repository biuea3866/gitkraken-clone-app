package dev.undine.presentation.commitdetail

private const val LINE_FEED = '\n'
private const val CARRIAGE_RETURN = "\r"
private const val EMPTY = ""

/**
 * 커밋 메시지를 제목(첫 줄)과 본문(나머지)으로 나눈 결과.
 *
 * git 관례대로 첫 줄이 제목이고 그 뒤가 본문이다. 렌더링과 무관한 순수 규칙이라 화면 없이 검증한다.
 *
 * @property body 앞뒤 공백을 털어낸 본문. 공백뿐이면 빈 문자열이 되어 [hasBody] 가 거짓이다 —
 *   접을 것이 없는데 접기 조작을 그리면 사용자가 빈 영역을 열게 된다.
 */
data class CommitMessageParts(val subject: String, val body: String) {

    val hasBody: Boolean get() = body.isNotEmpty()

    companion object {

        fun of(message: String): CommitMessageParts {
            // CRLF 저장소와 LF 저장소가 같은 결과를 내야 한다.
            val normalized = message.replace(CARRIAGE_RETURN, EMPTY)
            return CommitMessageParts(
                subject = normalized.substringBefore(LINE_FEED).trim(),
                body = normalized.substringAfter(LINE_FEED, EMPTY).trim(),
            )
        }
    }
}
