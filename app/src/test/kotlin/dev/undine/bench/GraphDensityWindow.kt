package dev.undine.bench

import dev.undine.domain.Commit
import dev.undine.domain.graph.GraphLaneAssigner
import dev.undine.presentation.graph.CommitRefIndex
import dev.undine.presentation.graph.GraphRowItem

/**
 * 밀도를 봤다고 인정하는 최소 레인 수.
 *
 * 둘이면 `refs/heads/main` 단독 이력과 구분되지 않는다 — 그 화면은 `ScreenshotRenderSpec` 의
 * 픽스처 렌더가 이미 남기고 있어, 같은 것을 한 장 더 남기는 셈이 된다.
 */
internal const val DENSITY_MINIMUM_LANES = 3

/**
 * 화면에 들어가는 행 수. 렌더 높이를 이 값에서 거꾸로 정한다 ([GraphDensityRenderSpec]).
 *
 * 판정 범위를 화면 크기에 맞춰 두는 이유는, 목록 전체로 판정하면 **화면 밖의 배지**를 근거로
 * "배지가 보인다" 고 단언하게 되기 때문이다. 실제로 그 착오로 배지 없는 그림이 나왔다.
 */
internal const val DENSITY_VISIBLE_ROWS = 14

/** 한 번에 실어 둘 커밋 수. 보이는 행보다 넉넉해야 렌더 도중 다음 페이지 요청이 뜨지 않는다. */
internal const val DENSITY_WINDOW_SIZE = 40

/**
 * 이력에서 **레인이 여럿이고 ref 배지가 화면 안에 있는** 구간을 고른다. 없으면 `null` 이다.
 *
 * 구간을 고르는 것이지 데이터를 만드는 것이 아니다 — 반환값은 언제나 [history] 의 연속된 조각이다.
 * 조건을 만족하는 구간이 없으면 임의로 완화하지 않고 `null` 을 돌려준다. 완화하면 "밀도를 봤다"
 * 는 결론만 남고 정작 볼 것이 없는 그림이 나온다.
 */
internal fun selectDenseWindow(history: List<Commit>, refIndex: CommitRefIndex): List<Commit>? =
    denseWindowStarts(history, refIndex)
        .asSequence()
        .map { start -> history.drop(start).take(DENSITY_WINDOW_SIZE) }
        .firstOrNull { window -> isDenseEnough(window, refIndex) }

/**
 * 구간 시작 후보 — 맨 앞과 **배지가 붙은 커밋을 화면 안에 두는 위치**들이다.
 *
 * 배지가 붙은 커밋을 맨 윗행에 올리면 그 배지는 반드시 화면 안에 들어온다. 브랜치 팁이 목록
 * 아래쪽에 몰려 있어도 한 장에 배지가 잡히는 것은 이 후보 덕이다.
 *
 * 다만 **이력 끝에서 [DENSITY_VISIBLE_ROWS] 행이 남지 않는 배지**는 맨 윗행에 올리면 화면을
 * 채우지 못해 그 구간이 통째로 기각된다 — 배지를 보이게 하는 앞선 구간이 있는데도 `null` 이
 * 나온다. 그래서 배지를 **맨 아랫행**에 두는 시작점도 함께 후보로 둔다.
 *
 * 순서는 배지를 위에 두는 쪽이 먼저다. 배지가 위에 오면 그 아래로 이력이 이어지는 그림이라
 * 밀도를 보기에 낫고, 아랫행 후보는 그 방법이 통하지 않을 때의 차선이다.
 */
private fun denseWindowStarts(history: List<Commit>, refIndex: CommitRefIndex): List<Int> {
    val badged = history.indices.filter { refIndex.chipsFor(history[it].id).isNotEmpty() }
    val badgeAtBottom = badged.map { it - (DENSITY_VISIBLE_ROWS - 1) }.filter { it > 0 }
    return (listOf(0) + badged + badgeAtBottom).distinct()
}

/**
 * 이 구간이 밀도 검토 재료로 쓸 만한가.
 *
 * 화면을 다 채우지 못하는 꼬리 구간은 인정하지 않는다 — 아래가 빈 그림은 행 간격을 판단할 수 없다.
 */
private fun isDenseEnough(window: List<Commit>, refIndex: CommitRefIndex): Boolean {
    val visible = visibleRowsOf(window)
    return visible.size >= DENSITY_VISIBLE_ROWS &&
        visible.any { refIndex.chipsFor(it.commit.id).isNotEmpty() } &&
        visible.maxOf { it.laneSpan } >= DENSITY_MINIMUM_LANES
}

/**
 * 구간의 앞부분 — 실제로 그려지는 행이다.
 *
 * 레인 배치는 화면과 같은 경로([GraphLaneAssigner])로, 같은 조건(직전 페이지 없음)에서 계산한다.
 * 다른 방법으로 세면 판정과 그림이 갈린다.
 */
internal fun visibleRowsOf(window: List<Commit>): List<GraphRowItem> =
    window.zip(GraphLaneAssigner.assign(window).rows) { commit, row ->
        GraphRowItem(commit = commit, row = row)
    }.take(DENSITY_VISIBLE_ROWS)
