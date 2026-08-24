package dev.undine.presentation.welcome

import androidx.compose.runtime.Immutable
import dev.undine.domain.Progress

/**
 * [WelcomeScreen] 이 그리는 데 필요한 **읽기 전용 스냅샷** 전부.
 *
 * 화면은 이 값과 [WelcomeEvents] 만 받고 UseCase·Gateway 를 알지 못한다 (compose-ui 규칙 1).
 * 소유자는 [WelcomeState] 이며, 한 값으로 묶어 두면 화면 시그니처가 상태 항목 추가마다 늘어나지 않는다.
 *
 * @property cloneProgress 진행량을 아직 모르는 구간에서는 `null` 이다 — 0% 로 꾸미지 않는다.
 * @property cloneUrl clone 입력란의 원격 주소. 입력 중인 글자도 화면 상태이므로 홀더가 소유한다.
 * @property cloneTarget clone 입력란의 대상 디렉터리 경로.
 */
@Immutable
data class WelcomeScreenState(
    val recentRepositories: List<RecentRepository> = emptyList(),
    val cloning: Boolean = false,
    val cloneProgress: Progress? = null,
    val notice: WelcomeNotice? = null,
    val cloneUrl: String = "",
    val cloneTarget: String = "",
)
