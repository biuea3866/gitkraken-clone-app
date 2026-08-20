package dev.undine.presentation.i18n

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private const val NAMESPACE = "time.relative"
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 60L * 60
private const val SECONDS_PER_DAY = 24L * 60 * 60
private const val RELATIVE_LIMIT_SECONDS = 30L * 24 * 60 * 60

/**
 * `time.relative.*` 키. JDK 에는 상대 시각 서식 API 가 없으므로 **경과 시간을 직접 계산한 뒤**
 * 이 키의 `MessageFormat` 패턴에 인자를 넘긴다 — 언어별 복수형 규칙은 패턴 안에 있다.
 */
object TimeKeys {
    val justNow = StringKey("$NAMESPACE.justNow")
    val minutesAgo = StringKey("$NAMESPACE.minutesAgo")
    val hoursAgo = StringKey("$NAMESPACE.hoursAgo")
    val daysAgo = StringKey("$NAMESPACE.daysAgo")
}

/**
 * 시각 표현 접근자. `strings.time.relative(instant, now)` 로 읽는다.
 *
 * 경계는 60초 · 60분 · 24시간 · 30일이며 30일을 넘으면 로케일 절대 날짜로 바꿔 표시한다.
 * **기준 시각 `now` 를 인자로 받는다** — 내부에서 `Instant.now()` 를 부르지 않으므로 테스트가 결정적이다.
 * 미래 시각(경과 시간이 음수)은 `방금 전` 으로 표시한다.
 */
@JvmInline
value class TimeStrings internal constructor(private val strings: Strings) {

    fun relative(instant: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val elapsedSeconds = Duration.between(instant, now).seconds
        return when {
            elapsedSeconds < SECONDS_PER_MINUTE -> strings.text(TimeKeys.justNow)
            elapsedSeconds < SECONDS_PER_HOUR ->
                strings.text(TimeKeys.minutesAgo, elapsedSeconds / SECONDS_PER_MINUTE)

            elapsedSeconds < SECONDS_PER_DAY ->
                strings.text(TimeKeys.hoursAgo, elapsedSeconds / SECONDS_PER_HOUR)

            elapsedSeconds < RELATIVE_LIMIT_SECONDS ->
                strings.text(TimeKeys.daysAgo, elapsedSeconds / SECONDS_PER_DAY)

            else -> strings.date(instant, zone)
        }
    }
}

/** 시각 표현 네임스페이스 진입점. */
val Strings.time: TimeStrings get() = TimeStrings(this)

internal val timeTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        // 한국어는 복수 구분이 없어 수량과 무관하게 한 형태다.
        TimeKeys.justNow to "방금 전",
        TimeKeys.minutesAgo to "{0}분 전",
        TimeKeys.hoursAgo to "{0}시간 전",
        TimeKeys.daysAgo to "{0}일 전",
    ),
    Locale.ENGLISH to mapOf(
        // 영어는 one/other 두 형태 — MessageFormat 의 choice 로 리소스 안에서 갈린다.
        TimeKeys.justNow to "just now",
        TimeKeys.minutesAgo to "{0,choice,1#{0} minute ago|1<{0} minutes ago}",
        TimeKeys.hoursAgo to "{0,choice,1#{0} hour ago|1<{0} hours ago}",
        TimeKeys.daysAgo to "{0,choice,1#{0} day ago|1<{0} days ago}",
    ),
)
