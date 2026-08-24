package dev.undine.presentation

/**
 * 커맨드가 부를 동작 묶음. 하나씩 인자로 받으면 커맨드가 늘어날 때마다 시그니처가 길어진다.
 *
 * 이름으로 읽어 쓴다 (`handlers.onOpenPalette`) — 구조 분해로 다섯 개를 한 줄에 풀면 순서를
 * 잘못 맞춰도 컴파일이 되므로 커맨드에 다른 동작이 붙는다.
 */
class AppCommandHandlers(
    val onOpenPalette: () -> Unit,
    val onCloseRepository: () -> Unit,
    val onRefreshRefs: () -> Unit,
    val onToggleDiffView: () -> Unit,
    val onOpenRebasePlan: () -> Unit,
)
