package dev.undine.presentation.preferences

import dev.undine.presentation.i18n.PreferencesStrings
import java.io.IOException

/**
 * 즉시 저장이 끝내 반영되지 못한 사유.
 *
 * **두 실패는 사용자가 할 일이 다르다.** 값이 거부된 것은 입력을 고쳐야 하고, 쓰기 실패는 디스크·권한
 * 문제라 다시 시도할 일이다. 하나의 문구로 뭉개면 무엇을 고쳐야 하는지 알 수 없다. `sealed` 라
 * 문구 선택([messageIn])이 새 사유를 조용히 빠뜨리지 않는다.
 *
 * 두 경우 모두 **파일은 그대로**다 — 거부는 저장 전 `Settings` 생성에서, 쓰기 실패는 임시 파일 교체
 * 전에 일어난다. 그래서 화면은 저장된 값에 머물면 되고 되돌릴 대상이 없다.
 */
sealed interface PreferencesSaveFailure {

    val cause: Exception

    /** 값이 허용 범위를 벗어나 domain 이 거부했다. 저장을 시작하지도 않았다. */
    data class Rejected(override val cause: IllegalArgumentException) : PreferencesSaveFailure

    /** 값은 유효했지만 설정 파일에 쓰지 못했다. */
    data class NotWritten(override val cause: IOException) : PreferencesSaveFailure
}

/** 화면에 보일 문구. 문자열은 리소스에서만 온다. */
fun PreferencesSaveFailure.messageIn(texts: PreferencesStrings): String = when (this) {
    is PreferencesSaveFailure.Rejected -> texts.invalidValue
    is PreferencesSaveFailure.NotWritten -> texts.saveFailed
}
