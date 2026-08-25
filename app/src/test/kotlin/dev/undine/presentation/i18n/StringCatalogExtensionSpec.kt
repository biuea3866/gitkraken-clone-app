package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 세 번째 로케일은 **리소스 데이터 추가만으로** 붙는다 — 조회 호출 코드는 한 줄도 바뀌지 않는다. */
private val germanCommonTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.GERMAN to mapOf(
        CommonKeys.ok to "OK",
        CommonKeys.cancel to "Abbrechen",
        CommonKeys.retry to "Wiederholen",
        CommonKeys.close to "Schließen",
    ),
)

/** UND-63 이 빈 채로 등록한 네임스페이스를 소비 티켓(UND-40)이 채우는 모습. 한국어만 채운 상태다. */
private val PREFERENCES_TITLE = StringKey("$PREFERENCES_NAMESPACE.title")

private val filledPreferencesTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    DEFAULT_LOCALE to mapOf(PREFERENCES_TITLE to "환경설정"),
)

class StringCatalogExtensionSpec : FunSpec({

    val catalog = StringCatalog(
        translations = mergeTranslations(builtInTranslations + germanCommonTranslations),
        defaultLocale = DEFAULT_LOCALE,
    )
    val german = catalog.stringsFor(Locale.GERMAN, devBuild = false)

    test("추가한 로케일이 지원 목록에 들어간다") {
        catalog.supportedLocales shouldContainAll setOf(Locale.KOREAN, Locale.ENGLISH, Locale.GERMAN)
        catalog.resolveLocale(Locale.GERMANY) shouldBe Locale.GERMAN
    }

    test("기존 조회 호출이 변경 없이 추가한 로케일 값을 반환한다") {
        german.common.cancel shouldBe "Abbrechen"
        german.common.close shouldBe "Schließen"
    }

    test("추가한 로케일에 없는 키는 기본 로케일 값으로 폴백한다") {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-08-20T12:00:00Z")

        german.time.relative(now.minus(Duration.ofDays(3)), now, zone) shouldBe "3일 전"
    }

    test("추가한 로케일의 숫자 형식도 그 로케일을 따른다") {
        german.number(1234.5) shouldBe "1.234,5"
    }

    test("기존 두 로케일 조회는 영향받지 않는다") {
        catalog.stringsFor(Locale.KOREAN, devBuild = false).common.cancel shouldBe "취소"
        catalog.stringsFor(Locale.ENGLISH, devBuild = false).common.cancel shouldBe "Cancel"
    }

    test("빈 스텁 네임스페이스를 나중에 채워도 기존 등록을 고치지 않고 그대로 병합된다") {
        // 소비 티켓은 자기 파일의 맵만 채운다 — registry 의 기존 preferences 자리에 주입된다.
        val filled = StringCatalog(
            translations = mergeTranslations(builtInTranslationSources(preferences = filledPreferencesTranslations)),
            defaultLocale = DEFAULT_LOCALE,
        )

        filled.stringsFor(DEFAULT_LOCALE, devBuild = false).text(PREFERENCES_TITLE) shouldBe "환경설정"
        filled.stringsFor(Locale.ENGLISH, devBuild = false).common.ok shouldBe "OK"
    }

    test("채운 스텁 네임스페이스의 키가 다른 로케일에 없으면 기본 로케일로 폴백한다") {
        val filled = StringCatalog(
            translations = mergeTranslations(builtInTranslationSources(preferences = filledPreferencesTranslations)),
            defaultLocale = DEFAULT_LOCALE,
        )

        filled.stringsFor(Locale.ENGLISH, devBuild = false).text(PREFERENCES_TITLE) shouldBe "환경설정"
    }
})
