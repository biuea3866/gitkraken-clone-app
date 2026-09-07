package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

/**
 * 저장된 `Settings.language` 태그가 어느 카탈로그로 그려지는지 — 배선(`AppRoot`)과 설정 화면이
 * **같은 규칙**을 쓰는지 본다. 두 곳이 갈리면 설정 화면이 보여주는 언어와 실제 문구가 어긋난다.
 *
 * 문구 단언에 `systemStrings()` 를 쓰지 않는다 — 기계의 로케일에 따라 결과가 갈린다 (#125).
 */
class SettingsLocaleSpec : FunSpec({

    val catalog = builtInStringCatalog()

    test("저장된 한국어 태그는 한국어 문구를 고른다") {
        catalog.localeForLanguageTag("ko") shouldBe Locale.KOREAN
        catalog.stringsForLanguageTag("ko").welcome.title shouldBe "저장소 열기"
    }

    test("저장된 영어 태그는 영어 문구를 고른다") {
        catalog.localeForLanguageTag("en") shouldBe Locale.ENGLISH
        catalog.stringsForLanguageTag("en").welcome.title shouldBe "Open a repository"
    }

    test("지역 변종 태그는 같은 언어의 번역으로 폴백한다") {
        catalog.localeForLanguageTag("en-GB") shouldBe Locale.ENGLISH
    }

    test("카탈로그가 모르는 태그는 기본 로케일로 해석되고 문구가 비지 않는다") {
        catalog.localeForLanguageTag("fr-FR") shouldBe DEFAULT_LOCALE
        catalog.stringsForLanguageTag("fr-FR").welcome.title shouldBe "저장소 열기"
    }

    test("언어가 null 이면 시스템 로케일을 따른다") {
        catalog.localeForLanguageTag(null) shouldBe null
        withDefaultLocale(Locale.ENGLISH) {
            catalog.stringsForLanguageTag(null).welcome.title
        } shouldBe "Open a repository"
        withDefaultLocale(Locale.KOREAN) {
            catalog.stringsForLanguageTag(null).welcome.title
        } shouldBe "저장소 열기"
    }
})
