package dev.undine.domain.identity

private const val SCHEME_SEPARATOR = "://"

/** 호스트로 인정하는 형태. 경로·백슬래시가 섞이면 호스트가 아니다. */
private val HOST_SHAPE = Regex("[a-z0-9._-]+")

/**
 * 원격 URL 또는 사용자가 적은 예상 호스트에서 **비교 가능한 호스트**만 뽑는다.
 *
 * 소문자로 낮추고 userinfo(`git@`)와 포트를 지운다 — 같은 호스트를 다르게 적었다는 이유로
 * 경고가 뜨면 기능이 방해가 된다. URL 형식(`https://host/path`·`ssh://git@host:22/path`)과
 * scp 형식(`git@host:path`), 호스트만 적은 값을 받는다. 경로·스킴은 비교하지 않는다.
 *
 * **판단할 수 없으면 null 이다.** 로컬 경로 원격처럼 호스트가 없는 값은 실패가 아니라 경고를
 * 건너뛸 근거이므로, 여기서 오류를 만들지 않는다.
 */
fun normalizedHostOf(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val authority = if (trimmed.contains(SCHEME_SEPARATOR)) {
        trimmed.substringAfter(SCHEME_SEPARATOR).substringBefore('/')
    } else {
        // scp 형식은 첫 콜론 앞이 `[userinfo@]host` 다. 호스트만 적은 값도 이 경로로 지나간다.
        trimmed.substringBefore(':')
    }
    val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
    return host.takeIf { candidate -> HOST_SHAPE.matches(candidate) }
}
