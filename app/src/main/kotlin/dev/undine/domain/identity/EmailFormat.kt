package dev.undine.domain.identity

import dev.undine.domain.UndineException

/**
 * `로컬부@도메인.최상위` 형태만 통과시킨다.
 *
 * 공백과 `@`·`<`·`>`·`,`·`"` 를 배제하는 이유는 그 문자들이 들어가면 git 이 `user.email` 을
 * 그대로 다루지 못하기 때문이다. 도메인에 점을 요구하는 것은 `me@localhost` 같은 값보다
 * `notanemail` · `me@` 같은 **오타**를 잡는 쪽이 훨씬 흔하기 때문이다.
 */
private val EMAIL_FORMAT = Regex("""[^\s@<>,"]+@[^\s@<>,".]+(\.[^\s@<>,".]+)+""")

/**
 * 이메일 **형식**만 판정한다 — 실재 여부·도달 가능성은 확인하지 않는다.
 *
 * 이 판단을 domain 이 소유하는 이유는 진입점(새 프로필·프로필 수정)이 늘 때마다 같은 규칙을 다시
 * 쓰게 되고, 한 곳만 틀려도 조용히 통과하기 때문이다.
 */
fun isValidEmailFormat(email: String): Boolean = EMAIL_FORMAT.matches(email)

/**
 * 형식이 틀린 이메일을 **쓰기 경로에서만** 거부한다.
 *
 * 읽기에는 걸지 않는다 — 이미 저장된 잘못된 이메일이 앱 시작이나 프로필 조회를 막으면 그것은
 * 검증이 아니라 잠금이다.
 *
 * @throws UndineException.StateViolation 형식이 올바르지 않을 때
 */
fun requireValidEmailFormat(email: String) {
    if (!isValidEmailFormat(email)) {
        throw UndineException.StateViolation("이메일 형식이 올바르지 않습니다: '$email'")
    }
}
