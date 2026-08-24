package dev.undine.presentation.search

/**
 * 검색 화면이 노출하는 입력 축. 축마다 화면에 입력칸 하나가 대응한다.
 *
 * @property isDate 값을 날짜로 읽는 축. 형식에 맞지 않는 입력은 조건에서 빠지고 화면이 그 사실을 알린다
 *   ([SearchState.isInvalid]) — 입력 도중의 문자열을 오류로 취급해 검색을 막지 않기 위해서다.
 */
enum class SearchField(val isDate: Boolean) {
    MESSAGE(isDate = false),
    AUTHOR(isDate = false),
    HASH(isDate = false),
    PATH(isDate = false),
    SINCE(isDate = true),
    UNTIL(isDate = true),
}
