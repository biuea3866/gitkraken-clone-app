package dev.undine.presentation.preferences

import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.preferences
import java.util.Locale

/** 행 조립 판정에 쓰는 실제 한국어 문구. 누락 키가 있으면 조회 자체가 키 이름을 돌려줘 눈에 띈다. */
internal val PREFERENCES_TEST_STRINGS = builtInStringCatalog()
    .stringsFor(Locale.KOREAN, devBuild = false)
    .preferences
