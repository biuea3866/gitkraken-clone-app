package dev.undine.presentation.rebase

import kotlin.math.roundToInt

/**
 * 드래그한 세로 거리로 **놓을 자리**를 정한다. 행 높이의 절반을 넘길 때마다 한 칸 움직인다.
 *
 * 제스처 코드에서 떼어 둔 이유는 이 계산이 재정렬의 실제 규칙이기 때문이다 — 마우스 이벤트를
 * 흉내내는 화면 테스트는 렌더 크기·타이밍에 흔들리지만, 이 함수는 그대로 검증된다.
 *
 * @param rowHeightPx 아직 측정되지 않았으면 0 이 들어온다 — 그때는 움직이지 않는다(0 으로 나누지 않는다).
 */
fun dropTargetIndex(from: Int, dragOffsetPx: Float, rowHeightPx: Float, count: Int): Int {
    if (count <= 0 || rowHeightPx <= 0f) return from
    val moved = (dragOffsetPx / rowHeightPx).roundToInt()
    return (from + moved).coerceIn(0, count - 1)
}
