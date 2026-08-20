package dev.undine.infrastructure.git.remote

/**
 * 예외에 실을 원격 식별자. 사용자에게 보이는 것은 [label](`origin` 등) 뿐이고,
 * [url] 은 **마스킹 대상**으로만 쓴다 — 예외나 로그에 싣지 않는다.
 */
internal data class RemoteIdentity(val label: String, val url: String?)

/**
 * 원격 URL 을 원격 이름으로 통째로 치환한다.
 *
 * JGit 의 전송 예외 메시지는 원격 URL 을 그대로 담는다. 자격증명 구간만 가리면 토큰은 빠지지만
 * 호스트·경로는 남는데, 사설 저장소 주소 자체가 민감할 수 있다. 그래서 URL 전체를 지운다 —
 * `.agent/rules/credential-handling.md` 2번.
 */
internal object RemoteUrlMasking {

    /** `scheme://host/path` 와 scp 형식(`git@host:path`) 둘 다 지운다. */
    private val URL_PATTERNS = listOf(
        Regex("""[A-Za-z][A-Za-z0-9+.\-]*://\S*"""),
        Regex("""[A-Za-z0-9._\-]+@[A-Za-z0-9._\-]+:\S*"""),
    )

    /**
     * [text] 안의 원격 주소를 [remote] 의 이름으로 바꾼다.
     *
     * 알고 있는 원본 URL 을 먼저 지우고(로컬 파일 경로 원격처럼 패턴으로 잡히지 않는 형태를 위해),
     * 남은 URL 형태를 패턴으로 지운다.
     */
    fun mask(text: String?, remote: RemoteIdentity): String {
        if (text.isNullOrEmpty()) return ""
        val withoutKnownUrl = remote.url
            ?.takeIf { it.isNotBlank() }
            ?.let { url -> text.replace(url, remote.label) }
            ?: text
        return URL_PATTERNS.fold(withoutKnownUrl) { masked, pattern -> pattern.replace(masked, remote.label) }
    }
}
