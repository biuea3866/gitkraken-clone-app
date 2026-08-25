package dev.undine.domain.signing

/**
 * 저장소의 서명 설정. **앱이 따로 보관하지 않고 git 설정을 그대로 읽는다** — 앱이 자기 사본을
 * 만들면 사용자가 `git config` 로 바꾼 값과 어긋난다.
 *
 * @param signCommits `commit.gpgsign`
 * @param signTags `tag.gpgSign`
 * @param format `gpg.format` (미설정이면 [SigningFormat.OPENPGP] — git 의 기본값)
 * @param signingKey `user.signingkey`. 없을 수 있다 — GPG 는 기본 키로 서명할 수 있지만
 *   SSH 는 키를 지정하지 않으면 서명할 수 없다.
 */
data class SigningSettings(
    val signCommits: Boolean,
    val signTags: Boolean,
    val format: SigningFormat,
    val signingKey: String?,
)
