package dev.undine.presentation.i18n

import dev.undine.domain.ChangeType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `commitdetail.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class CommitDetailStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = commitDetailTranslations, defaultLocale = DEFAULT_LOCALE)

    /**
     * 로케일 리소스가 [CommitDetailKeys.all] 과 정확히 일치하는지 본다. 접근자를 하나씩 호출하는
     * 검사는 기본 로케일 폴백에 가려 비기본 로케일의 누락을 놓치고, 열거에서 빠진 키(`person`·`timestamp`)는
     * 아예 검사되지 않았다. 맵의 키 집합을 직접 대조하면 두 구멍이 함께 막힌다.
     */
    test("지원 로케일마다 commitdetail 키가 빠짐없이, 남김없이 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            commitDetailTranslations.getValue(locale).keys shouldBe CommitDetailKeys.all.toSet()
        }
    }

    test("지원 로케일마다 모든 문구가 값으로 채워져 나온다") {
        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).commitDetail
            val rendered = listOf(
                strings.hash,
                strings.copyHash,
                strings.author,
                strings.authoredAt,
                strings.committer,
                strings.committedAt,
                strings.parents,
                strings.noParents,
                strings.baseParent,
                strings.expandMessage,
                strings.collapseMessage,
                strings.changedFiles,
                strings.loading,
                strings.noChanges,
                strings.noChangesDescription,
                strings.loadFailed,
                strings.binary,
                strings.person(name = "Hana Kim", email = "hana@undine.dev"),
                strings.timestamp(date = "2026-03-04", timeOfDay = "05:06"),
                strings.parentOption(1),
                strings.lineStats(added = 3, deleted = 2),
                strings.renamedFrom("old/path.kt"),
            ) + ChangeType.entries.map { strings.changeType(it) }

            rendered.forEach {
                it.shouldNotBeBlank()
                it shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("이름·메일과 날짜·시각은 로케일마다 인자를 문구에 채워 넣는다") {
        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).commitDetail

            val person = strings.person(name = "Hana Kim", email = "hana@undine.dev")
            person shouldContain "Hana Kim"
            person shouldContain "hana@undine.dev"

            val timestamp = strings.timestamp(date = "2026-03-04", timeOfDay = "05:06")
            timestamp shouldContain "2026-03-04"
            timestamp shouldContain "05:06"
        }
    }

    test("변경 종류는 종류마다 다른 문구로 나온다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).commitDetail

        ChangeType.entries.map { korean.changeType(it) }.toSet().size shouldBe ChangeType.entries.size
    }

    test("증감 줄 수와 부모 번호는 인자를 문구에 채워 넣는다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).commitDetail

        korean.lineStats(added = 12, deleted = 4) shouldContain "12"
        korean.lineStats(added = 12, deleted = 4) shouldContain "4"
        korean.parentOption(2) shouldContain "2"
        korean.renamedFrom("old/path.kt") shouldContain "old/path.kt"
    }

    test("로케일마다 다른 문구가 나온다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).commitDetail
        val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).commitDetail

        korean.author shouldBe "작성자"
        english.author shouldBe "Author"
        korean.noChanges shouldBe "변경된 파일이 없습니다"
        english.noChanges shouldBe "No files changed"
    }

    test("commitdetail 키는 commitdetail 네임스페이스 접두사를 쓴다") {
        CommitDetailKeys.all.forEach { it.id.startsWith("commitdetail.") shouldBe true }
    }
})
