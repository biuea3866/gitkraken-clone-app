package dev.undine.presentation.commitdetail

import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.commitDetailTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import java.time.ZoneId
import java.util.Locale

private val SEOUL = ZoneId.of("Asia/Seoul")

private fun stringsFor(locale: Locale) =
    StringCatalog(commitDetailTranslations, DEFAULT_LOCALE).stringsFor(locale, devBuild = false)

/**
 * 커밋 시각 표시 — 로케일·시간대를 인자로 받아 결정적으로 검증한다.
 * 상대 시각(`strings.time.relative`)이 아니라 절대 시각을 쓴다: 상세 패널은 정확한 시점을 보여야 한다.
 */
class CommitTimestampSpec : FunSpec({

    test("작성 시각을 주어진 시간대의 날짜와 시각으로 보여준다") {
        val formatted = formatCommitTimestamp(stringsFor(DEFAULT_LOCALE), AUTHORED_AT, SEOUL)

        // 2026-03-04T05:06:07Z 는 서울에서 3월 4일 14시 6분이다.
        formatted shouldContain "2026"
        formatted shouldContain "14"
    }

    test("로케일이 다르면 서식도 달라지고 어느 쪽도 비어 있지 않다") {
        val korean = formatCommitTimestamp(stringsFor(Locale.KOREAN), COMMITTED_AT, SEOUL)
        val english = formatCommitTimestamp(stringsFor(Locale.ENGLISH), COMMITTED_AT, SEOUL)

        korean.shouldNotBeBlank()
        english.shouldNotBeBlank()
        english shouldContain "2026"
    }
})
