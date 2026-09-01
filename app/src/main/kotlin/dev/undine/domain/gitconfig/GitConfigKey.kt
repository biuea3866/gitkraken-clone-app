package dev.undine.domain.gitconfig

/**
 * 실효값을 조회하는 Git 설정 키. **설정 화면이 다루는 항목이 곧 이 목록**이고, 여기 없는 키는
 * 조회 대상이 아니다 — 목록을 넓게 상상하지 않는다 (결정 G34 UND-75 2).
 *
 * 서명 키가 셋인 이유는 `SigningGatewayImpl` 이 읽는 범위가 곧 이 앱의 서명 범위이기 때문이다:
 * [COMMIT_GPGSIGN] · [GPG_FORMAT] · [USER_SIGNING_KEY].
 *
 * [name] 은 **Git 이 쓰는 철자 그대로**다 — `signingKey` 로 적으면 Git 이 읽는 키와 어긋난다.
 */
enum class GitConfigKey(val section: String, val key: String) {
    INIT_DEFAULT_BRANCH("init", "defaultBranch"),
    PULL_REBASE("pull", "rebase"),
    DIFF_TOOL("diff", "tool"),
    MERGE_TOOL("merge", "tool"),
    USER_NAME("user", "name"),
    USER_EMAIL("user", "email"),
    COMMIT_GPGSIGN("commit", "gpgsign"),
    GPG_FORMAT("gpg", "format"),
    USER_SIGNING_KEY("user", "signingkey"),
    ;

    /** `git config` 가 쓰는 표기 (`user.email`). 화면이 사용자에게 보여 줄 이름이기도 하다. */
    val qualifiedName: String get() = "$section.$key"
}
