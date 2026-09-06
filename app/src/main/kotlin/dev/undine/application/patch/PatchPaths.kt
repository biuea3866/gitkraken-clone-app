package dev.undine.application.patch

private const val FILE_HEADER = "diff --git "
private const val OLD_PREFIX = "a/"
private const val NEW_PREFIX = "b/"
private const val QUOTE = '"'
private const val ESCAPE = '\\'

/**
 * 패치가 건드리는 경로. 생성 결과를 저장하기 **전에** 무엇이 들어가는지 보여 주는 재료다.
 *
 * 새 경로(`b/`)를 쓴다 — rename 이 섞이면 사용자가 결과 저장소에서 보게 될 이름이 그쪽이다.
 *
 * **git 의 경로 인용 규칙을 해석한다.** 비 ASCII·탭·따옴표·역슬래시가 든 경로는 헤더에서 큰따옴표로
 * 감싸이고 이스케이프된다(`"b/\355\225\234.txt"`). 정규식으로 `b/(.+)` 만 떼면 한글 경로 저장소에서
 * 목록이 통째로 어긋나, 목록을 둔 이유("의도치 않게 큰 패치를 만드는 걸 막는다")가 무력해진다.
 *
 * **읽지 못한 헤더는 건너뛰지 않는다** — 목록에서 조용히 빠지는 것이 이 결함의 본질이었다. 해석하지
 * 못하면 헤더 원문을 그대로 남겨, 무엇이 들어가는지 못 읽었다는 사실이 화면에 드러나게 한다.
 */
fun patchPathsOf(patch: ByteArray): List<String> =
    patch.toString(Charsets.UTF_8)
        .lineSequence()
        .filter { line -> line.startsWith(FILE_HEADER) }
        .map { line -> line.substring(FILE_HEADER.length) }
        .map { header -> newPathOf(header) ?: header }
        .distinct()
        .toList()

/** 헤더 한 줄에서 새 경로만 꺼낸다. 인용을 풀 수 없거나 `b/` 로 시작하지 않으면 `null`. */
private fun newPathOf(header: String): String? =
    newPathTokenOf(header)
        ?.let(::unquoteGitPath)
        ?.takeIf { path -> path.startsWith(NEW_PREFIX) }
        ?.removePrefix(NEW_PREFIX)

/**
 * 두 경로 중 **뒤엣것**의 원문 토큰. 인용 여부가 경로마다 따로 정해지므로 세 갈래다.
 *
 * rename 은 앞뒤 경로가 다르다 — 한쪽 규칙만 보면 rename 패치에서 목적지가 빠진다.
 */
private fun newPathTokenOf(header: String): String? = when {
    header.startsWith(QUOTE) -> tokenAfterQuoted(header)
    // 앞 경로가 인용되지 않았다면 그 안에 따옴표가 들어올 수 없다 — 첫 따옴표가 뒤 경로의 시작이다.
    header.contains(QUOTE) -> tokenAtQuote(header)
    else -> unquotedNewPathToken(header)
}

private fun tokenAfterQuoted(header: String): String? =
    quotedEnd(header)
        ?.takeIf { end -> end < header.length && header[end] == ' ' }
        ?.let { end -> header.substring(end + 1) }

private fun tokenAtQuote(header: String): String? =
    header.indexOf(QUOTE)
        .takeIf { at -> at > 0 && header[at - 1] == ' ' }
        ?.let(header::substring)

/** 여는 따옴표에 이어지는 닫는 따옴표의 **다음** 위치. 이스케이프된 따옴표는 닫지 않는다. */
private fun quotedEnd(header: String): Int? {
    var index = 1
    while (index < header.length && header[index] != QUOTE) {
        index += if (header[index] == ESCAPE) 2 else 1
    }
    return (index + 1).takeIf { index < header.length }
}

/**
 * 둘 다 인용되지 않은 경우. 인용되지 않은 경로에도 공백은 들어갈 수 있어 자를 자리가 여럿일 수 있다 —
 * 두 경로가 같아지는 자리를 먼저 고른다(rename 이 아닌 흔한 모양). 없으면 첫 자리를 쓴다.
 */
private fun unquotedNewPathToken(header: String): String? =
    header.takeIf { it.startsWith(OLD_PREFIX) }
        ?.let(::splitAt)
        ?.let { at -> header.substring(at + 1) }

private fun splitAt(header: String): Int? {
    val candidates = splitCandidatesOf(header)
    return candidates.firstOrNull { at -> samePathAt(header, at) } ?: candidates.firstOrNull()
}

private fun splitCandidatesOf(header: String): List<Int> {
    val separator = " $NEW_PREFIX"
    return generateSequence(header.indexOf(separator).takeIf { at -> at >= 0 }) { previous ->
        header.indexOf(separator, previous + 1).takeIf { at -> at >= 0 }
    }.toList()
}

private fun samePathAt(header: String, at: Int): Boolean =
    header.substring(OLD_PREFIX.length, at) == header.substring(at + 1 + NEW_PREFIX.length)
