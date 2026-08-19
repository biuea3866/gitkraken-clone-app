package dev.undine.domain

/**
 * 참조 이름 (`refs/heads/main`, `main`, `origin/main` 등).
 *
 * 이 티켓에서는 **검증 없는 값 래퍼**다. git 의 ref 이름 규칙(`..` 금지·`.lock` 접미사 금지·
 * ASCII 제어문자 금지 등)을 정확히 옮기지 않으면 정상 ref 를 거부하게 되므로,
 * 형식 검증은 이 값을 실제로 다루는 `RefGateway` 소유 티켓이 [UndineException.InvalidRefName] 과
 * 함께 구현한다.
 */
@JvmInline
value class RefName(val value: String) {

    override fun toString(): String = value
}
