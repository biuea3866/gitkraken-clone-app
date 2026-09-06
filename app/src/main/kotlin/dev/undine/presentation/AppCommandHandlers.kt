package dev.undine.presentation

/**
 * 커맨드가 부를 동작 묶음. 하나씩 인자로 받으면 커맨드가 늘어날 때마다 시그니처가 길어진다.
 *
 * 이름으로 읽어 쓴다 (`handlers.onOpenPalette`) — 구조 분해로 다섯 개를 한 줄에 풀면 순서를
 * 잘못 맞춰도 컴파일이 되므로 커맨드에 다른 동작이 붙는다.
 */
@Suppress("LongParameterList") // 커맨드가 부를 동작을 한데 모으는 묶음이다 — 명령 수만큼 늘어나는 것이 정상이다.
class AppCommandHandlers(
    val onOpenPalette: () -> Unit,
    val onCloseRepository: () -> Unit,
    val onRefreshRefs: () -> Unit,
    val onToggleDiffView: () -> Unit,
    val onOpenRebasePlan: () -> Unit,
    /**
     * 저장소를 바꾸는 명령을 막을 사유. 막지 않으면 `null` — `repositoryChangeBlockedReason` 이 답한다.
     *
     * **기본값을 두지 않는다.** 배선이 이 값을 빠뜨리면 경로를 잃은 탭에서 조작이 직전 저장소로
     * 조용히 새는데, 기본값이 있으면 그 누락이 컴파일에서도 드러나지 않는다.
     */
    val repositoryChangeBlockedReason: () -> String?,
    /**
     * 지금 화면이 진행 중인 작업 때문에 **저장소 전환**을 막는 사유. 막지 않으면 `null` —
     * `AppNavigationState.activeJobBlockedReason` 이 답한다 (결정 C6).
     *
     * [repositoryChangeBlockedReason] 과 나눠 두는 이유는 대상이 다르기 때문이다. 저쪽은 경로를 잃은
     * 탭에서 **그 저장소를 조작하는** 명령을 막고, 이쪽은 진행 중인 적용·저장이 있을 때 **활성 세션을
     * 바꾸는** 명령을 막는다. 하나로 합치면 경로를 잃은 탭에서 탭 닫기·새 저장소 열기까지 막혀
     * 사용자가 그 탭에 갇힌다.
     */
    val activeJobBlockedReason: () -> String?,
)
