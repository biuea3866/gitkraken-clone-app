package dev.undine.presentation.commitdetail

import dev.undine.presentation.i18n.Strings
import dev.undine.presentation.i18n.commitDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 24시간제 시각. 상세 패널은 커밋이 일어난 정확한 시점을 보여야 하고, 오전/오후 표기는
 * 목록에서 시각을 눈으로 비교할 때 읽는 비용이 크다. 날짜 부분은 로케일 서식을 그대로 쓴다.
 */
private val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 커밋 시각을 절대 시각 한 줄로 만든다 — 목록의 상대 시각(`strings.time.relative`)과 달리
 * 상세 패널은 정확한 시점을 보여야 한다.
 *
 * 기준 시간대를 인자로 받는다: 내부에서 시스템 시간대를 읽으면 검증이 실행 환경에 흔들린다.
 */
fun formatCommitTimestamp(
    strings: Strings,
    instant: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = strings.commitDetail.timestamp(
    date = strings.date(instant, zone),
    timeOfDay = TIME_OF_DAY.format(instant.atZone(zone)),
)
