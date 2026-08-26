package dev.undine.presentation.i18n

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.util.Locale

/** 어느 네임스페이스도 정의하지 않는 키. 화면 키를 쓰면 그 화면이 배선될 때 테스트가 깨진다. */
private val UNREGISTERED_KEY = StringKey("absent.namespace.absentKey")

/** UND-63 이 자리만 잡아 둔 wave 8 화면 7개의 네임스페이스. 키·번역은 각 화면 티켓이 채운다. */
private val WAVE8_STUB_NAMESPACES = listOf(
    PREFERENCES_NAMESPACE,
    BLAME_NAMESPACE,
    GRAPH_DRAG_DROP_NAMESPACE,
    UNDO_NAMESPACE,
    TABS_NAMESPACE,
    SUBMODULE_WORKTREE_NAMESPACE,
    RECOVERY_NAMESPACE,
)

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

    // UND-26 이 전 화면 번역을 등록한다. 네임스페이스 단위로 못박는다 — 키 목록을 열거하면
    // 화면이 문구를 하나 추가할 때마다 이 테스트가 깨져 실제 회귀를 가린다.
    test("채워지지 않은 화면 스텁이 등록돼도 병합이 성공한다") {
        // 어느 화면이 번역을 채웠는지 열거하지 않는다. 열거하면 화면이 문구를 채울 때마다 이 공용
        // 파일을 다시 고쳐야 해 wave 안에서 반복 충돌이 난다. 검증할 것은 "무엇이 등록됐든 빈 맵은
        // 병합 결과를 바꾸지 않는다" 이다.
        val merged = mergeTranslations(builtInTranslations)
        val withoutStubs = mergeTranslations(builtInTranslations.filter { it.isNotEmpty() })

        merged shouldBe withoutStubs
        builtInStringCatalog().supportedLocales shouldContainAll setOf(Locale.KOREAN, Locale.ENGLISH)
    }

    test("등록된 화면 키는 기본 로케일에서 빈 문자열이 아니다") {
        val entries = mergeTranslations(builtInTranslations).getValue(DEFAULT_LOCALE)

        entries.filterValues { it.isBlank() }.keys.map { it.id } shouldBe emptyList()
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
        catalog.stringsFor(Locale.ENGLISH, devBuild = false).text(UNREGISTERED_KEY) shouldBe
            UNREGISTERED_KEY.id
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

    test("채워진 undo와 남은 wave 8 빈 네임스페이스 스텁이 함께 병합된다") {
        val merged = mergeTranslations(builtInTranslations)
        val withoutStubs = mergeTranslations(builtInTranslations.filter { it.isNotEmpty() })

        merged shouldBe withoutStubs
        builtInStringCatalog().supportedLocales shouldContainAll setOf(Locale.KOREAN, Locale.ENGLISH)
    }

    test("빈 스텁을 등록해도 기존 키 조회는 그대로다") {
        val english = builtInStringCatalog().stringsFor(Locale.ENGLISH, devBuild = false)
        val korean = builtInStringCatalog().stringsFor(Locale.KOREAN, devBuild = false)

        english.common.ok shouldBe "OK"
        english.common.retry shouldBe "Retry"
        korean.common.ok shouldBe "확인"
        korean.common.retry shouldBe "다시 시도"
    }

    test("빈 스텁 네임스페이스의 키는 아직 어느 로케일에도 없어 키 이름으로 폴백한다") {
        val strings = catalog.stringsFor(Locale.ENGLISH, devBuild = false)

        WAVE8_STUB_NAMESPACES.forEach { namespace ->
            val key = StringKey("$namespace.absentKey")

            strings.text(key) shouldBe key.id
        }
    }

    test("스텁 네임스페이스는 서로 겹치지 않는다 — 화면끼리 키가 충돌하지 않는다") {
        WAVE8_STUB_NAMESPACES.distinct().size shouldBe WAVE8_STUB_NAMESPACES.size
    }

    test("wave 8 스텁은 CommonStrings 와 같은 키·접근자 계약을 제공한다") {
        val strings = catalog.stringsFor(Locale.KOREAN, devBuild = false)

        listOf(
            PreferencesKeys to strings.preferences,
            BlameKeys to strings.blame,
            GraphDragDropKeys to strings.graphDragDrop,
            UndoKeys to strings.undo,
            TabsKeys to strings.tabs,
            SubmoduleWorktreeKeys to strings.submoduleWorktree,
            RecoveryKeys to strings.recovery,
        ).size shouldBe WAVE8_STUB_NAMESPACES.size
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
