package dev.undine.presentation.preferences

/**
 * 고급 탭 고유 요소의 테스트 태그.
 *
 * 공용 [PreferencesTags] 는 탭 여섯 개가 함께 쓰는 파일이라, 한 탭만 쓰는 태그를 그쪽에 더하면
 * 탭 티켓들이 같은 파일을 동시에 고친다. 탭이 소유하는 것은 탭 파일에 둔다.
 */
object AdvancedPreferencesTags {

    /** 대용량 파일 임계치(바이트) 입력칸. */
    const val LARGE_FILE_THRESHOLD: String = "preferences.advanced.largeFileThreshold"

    /** 이력을 한 번에 읽을 개수 입력칸. */
    const val COMMIT_PAGE_SIZE: String = "preferences.advanced.commitPageSize"

    /**
     * 숫자로 읽을 수 없는 입력을 알리는 자리.
     *
     * 저장을 부르지도 못한 입력이라 공용 저장 실패([PreferencesTags.SAVE_FAILURE])와 자리가 다르다 —
     * 한 자리에 겹치면 "저장하다 실패했다" 와 "저장을 시작도 못 했다" 가 구분되지 않는다.
     */
    fun inputErrorOf(fieldTag: String): String = "$fieldTag.inputError"
}
