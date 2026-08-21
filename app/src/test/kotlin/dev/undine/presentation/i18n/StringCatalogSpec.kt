package dev.undine.presentation.i18n

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.Locale

private val UNREGISTERED_KEY = StringKey("graph.empty.title")

/**
 * 문자열 조회·로케일 결정·누락 키 폴백. 로케일 전환은 시스템 로케일만 다룬다 —
 * 사용자 설정 로케일은 이 티켓 범위 밖이다.
 */
class StringCatalogSpec : FunSpec({

    val catalog = builtInStringCatalog()

    test("공통 키는 한국어 문자열로 조회된다") {
        val strings = catalog.stringsFor(Locale.KOREAN, devBuild = false)

        strings.common.ok shouldBe "확인"
        strings.common.cancel shouldBe "취소"
        strings.common.retry shouldBe "다시 시도"
        strings.common.close shouldBe "닫기"
    }

    test("공통 키는 영어 문자열로 조회된다") {
        val strings = catalog.stringsFor(Locale.ENGLISH, devBuild = false)

        strings.common.ok shouldBe "OK"
        strings.common.cancel shouldBe "Cancel"
        strings.common.retry shouldBe "Retry"
        strings.common.close shouldBe "Close"
    }

    test("이 티켓이 등록하는 키는 공통 4개와 상대 시각 4개뿐이다") {
        val registeredKeys = mergeTranslations(builtInTranslations)
            .getValue(DEFAULT_LOCALE)
            .keys
            .map { it.id }

        registeredKeys shouldContainExactlyInAnyOrder listOf(
            "common.ok",
            "common.cancel",
            "common.retry",
            "common.close",
            "time.relative.justNow",
            "time.relative.minutesAgo",
            "time.relative.hoursAgo",
            "time.relative.daysAgo",
        )
    }

    test("키는 네임스페이스와 이름으로 나뉜다") {
        CommonKeys.ok.namespace shouldBe "common"
        CommonKeys.ok.name shouldBe "ok"
        TimeKeys.daysAgo.namespace shouldBe "time.relative"
        TimeKeys.daysAgo.name shouldBe "daysAgo"
    }

    test("네임스페이스가 없거나 구분자 위치가 잘못된 키는 만들 수 없다") {
        shouldThrow<IllegalArgumentException> { StringKey("ok") }
        shouldThrow<IllegalArgumentException> { StringKey("") }
        shouldThrow<IllegalArgumentException> { StringKey("common.") }
        shouldThrow<IllegalArgumentException> { StringKey(".ok") }
    }

    test("시스템 로케일을 전환하면 같은 조회 API 가 해당 언어를 반환한다") {
        withDefaultLocale(Locale.US) { systemStrings().common.ok } shouldBe "OK"
        withDefaultLocale(Locale.KOREA) { systemStrings().common.ok } shouldBe "확인"
    }

    test("지역 변형 로케일은 같은 언어의 번역을 쓴다") {
        catalog.resolveLocale(Locale.UK) shouldBe Locale.ENGLISH
        catalog.resolveLocale(Locale.KOREA) shouldBe Locale.KOREAN
    }

    test("미지원 로케일은 기본 로케일인 한국어로 폴백한다") {
        catalog.resolveLocale(Locale.FRENCH) shouldBe Locale.KOREAN
        withDefaultLocale(Locale.FRENCH) { systemStrings().common.ok } shouldBe "확인"
    }

    test("현재 로케일에 없는 키는 기본 로케일 값으로 폴백한다") {
        val partial = StringCatalog(
            translations = mapOf(
                Locale.KOREAN to mapOf(CommonKeys.ok to "확인"),
                Locale.ENGLISH to emptyMap(),
            ),
            defaultLocale = Locale.KOREAN,
        )

        partial.stringsFor(Locale.ENGLISH, devBuild = false).common.ok shouldBe "확인"
    }

    test("어느 로케일에도 없는 키는 크래시하지 않고 키 이름을 반환한다") {
        catalog.stringsFor(Locale.ENGLISH, devBuild = false).text(UNREGISTERED_KEY) shouldBe "graph.empty.title"
    }

    test("undine.dev=true 인 개발 빌드에서 누락 키는 앞뒤 표식으로 감싸 표시된다") {
        val displayed = withSystemProperty("undine.dev", "true") {
            builtInStringCatalog().stringsFor(Locale.ENGLISH).text(UNREGISTERED_KEY)
        }

        displayed shouldBe "$MISSING_KEY_MARKER${UNREGISTERED_KEY.id}$MISSING_KEY_MARKER"
        MISSING_KEY_MARKER shouldBe "!".repeat(2)
    }

    test("개발 빌드에서도 존재하는 번역은 그대로 반환한다") {
        val displayed = withSystemProperty("undine.dev", "true") {
            builtInStringCatalog().stringsFor(Locale.ENGLISH).common.ok
        }

        displayed shouldBe "OK"
    }

    test("기본 로케일 번역이 없는 카탈로그는 만들 수 없다") {
        shouldThrow<IllegalArgumentException> {
            StringCatalog(
                translations = mapOf(Locale.ENGLISH to mapOf(CommonKeys.ok to "OK")),
                defaultLocale = Locale.KOREAN,
            )
        }
    }
})
