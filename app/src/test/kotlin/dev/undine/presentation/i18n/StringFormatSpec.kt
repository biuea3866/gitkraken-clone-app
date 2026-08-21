package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 복수형·인자 치환·상대 시각·날짜·숫자 형식화. 기준 시각을 고정해 비결정성을 없앤다. */
class StringFormatSpec : FunSpec({

    val catalog = builtInStringCatalog()
    val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false)
    val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false)
    val zone = ZoneId.of("UTC")
    val now = Instant.parse("2026-08-20T12:00:00Z")

    test("영어는 수량 1에 단수형, 2 이상에 복수형을 쓴다") {
        english.time.relative(now.minusSeconds(60), now, zone) shouldBe "1 minute ago"
        english.time.relative(now.minusSeconds(120), now, zone) shouldBe "2 minutes ago"
        english.time.relative(now.minus(Duration.ofHours(1)), now, zone) shouldBe "1 hour ago"
        english.time.relative(now.minus(Duration.ofHours(5)), now, zone) shouldBe "5 hours ago"
        english.time.relative(now.minus(Duration.ofDays(1)), now, zone) shouldBe "1 day ago"
        english.time.relative(now.minus(Duration.ofDays(3)), now, zone) shouldBe "3 days ago"
    }

    test("한국어는 복수 구분이 없어 수량과 무관하게 같은 형태를 쓴다") {
        korean.time.relative(now.minusSeconds(60), now, zone) shouldBe "1분 전"
        korean.time.relative(now.minusSeconds(180), now, zone) shouldBe "3분 전"
        korean.time.relative(now.minus(Duration.ofDays(1)), now, zone) shouldBe "1일 전"
        korean.time.relative(now.minus(Duration.ofDays(3)), now, zone) shouldBe "3일 전"
    }

    test("인자 치환은 이어붙이기 없이 리소스 패턴으로 처리된다") {
        korean.text(TimeKeys.daysAgo, 3) shouldBe "3일 전"
        english.text(TimeKeys.daysAgo, 3) shouldBe "3 days ago"
    }

    test("인자에 큰 수를 넘기면 로케일 숫자 형식이 함께 적용된다") {
        english.text(TimeKeys.minutesAgo, 1234) shouldBe "1,234 minutes ago"
    }

    test("상대 시각 경계는 60초·60분·24시간이다") {
        korean.time.relative(now, now, zone) shouldBe "방금 전"
        korean.time.relative(now.minusSeconds(59), now, zone) shouldBe "방금 전"
        korean.time.relative(now.minusSeconds(60), now, zone) shouldBe "1분 전"
        korean.time.relative(now.minusSeconds(3599), now, zone) shouldBe "59분 전"
        korean.time.relative(now.minusSeconds(3600), now, zone) shouldBe "1시간 전"
        korean.time.relative(now.minus(Duration.ofDays(1)).plusSeconds(1), now, zone) shouldBe "23시간 전"
        korean.time.relative(now.minus(Duration.ofDays(1)), now, zone) shouldBe "1일 전"
    }

    test("30일 경계를 넘으면 절대 날짜로 표시한다") {
        val justInside = now.minus(Duration.ofDays(30)).plusSeconds(1)
        val onBoundary = now.minus(Duration.ofDays(30))

        korean.time.relative(justInside, now, zone) shouldBe "29일 전"
        korean.time.relative(onBoundary, now, zone) shouldBe korean.date(onBoundary, zone)
        korean.date(onBoundary, zone) shouldContain "2026"
    }

    test("날짜는 현재 로케일 형식을 따른다") {
        korean.date(now, zone) shouldNotBe english.date(now, zone)
        korean.date(now, zone) shouldContain "2026"
        english.date(now, zone) shouldContain "2026"
    }

    test("숫자는 현재 로케일 형식을 따른다") {
        english.number(1234567) shouldBe "1,234,567"
        korean.number(1234567) shouldBe "1,234,567"
    }
})
