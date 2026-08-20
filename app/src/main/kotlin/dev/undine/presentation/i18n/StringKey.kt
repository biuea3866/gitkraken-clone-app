package dev.undine.presentation.i18n

private const val NAMESPACE_SEPARATOR = '.'

/**
 * 문자열 리소스 키. `common.ok`·`graph.empty.title` 처럼 **네임스페이스 + 이름**을 점으로 잇는다 —
 * 평면 키는 화면이 늘어나면 금방 충돌한다.
 *
 * 화면 코드는 이 타입을 직접 만들지 않는다. 네임스페이스별 키 정의 객체([CommonKeys]·[TimeKeys])와
 * 타입 안전 접근자([CommonStrings]·[TimeStrings])만 쓰면 키 오타가 컴파일 시점에 잡힌다.
 *
 * 잘못된 형태의 키는 호출부 버그이므로 [IllegalArgumentException] 으로 막는다.
 */
@JvmInline
value class StringKey(val id: String) {

    init {
        require(id.isNotBlank()) { "문자열 키가 비어 있습니다" }
        require(id.contains(NAMESPACE_SEPARATOR)) { "문자열 키에 네임스페이스가 없습니다: $id" }
        require(!id.startsWith(NAMESPACE_SEPARATOR) && !id.endsWith(NAMESPACE_SEPARATOR)) {
            "문자열 키의 네임스페이스 구분자 위치가 잘못됐습니다: $id"
        }
    }

    /** `time.relative.daysAgo` → `time.relative`. */
    val namespace: String get() = id.substringBeforeLast(NAMESPACE_SEPARATOR)

    /** `time.relative.daysAgo` → `daysAgo`. */
    val name: String get() = id.substringAfterLast(NAMESPACE_SEPARATOR)

    override fun toString(): String = id
}
