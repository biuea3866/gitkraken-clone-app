package dev.undine.presentation

import androidx.compose.ui.input.key.Key
import dev.undine.presentation.graph.GraphOperationCallbacks
import dev.undine.presentation.graph.graphOperationCommands
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.palette.Command
import dev.undine.presentation.palette.CommandAvailability
import dev.undine.presentation.palette.CommandId
import dev.undine.presentation.palette.CommandRegistry
import dev.undine.presentation.palette.OPEN_COMMAND_PALETTE_SHORTCUT
import dev.undine.presentation.palette.Shortcut
import dev.undine.presentation.palette.ShortcutModifier
import java.awt.FileDialog
import java.awt.Frame
import dev.undine.domain.RepositoryPath

/**
 * 앱 동작을 커맨드 레지스트리에 등록한다.
 *
 * 등록을 여기 한 곳에 모으는 이유는 **단축키 충돌이 앱 시작 시점에 드러나야** 하기 때문이다 —
 * `CommandRegistry.register` 가 같은 단축키를 두 번 받으면 예외를 던진다. 화면마다 흩어 등록하면
 * 그 화면이 처음 열릴 때까지 충돌이 숨는다.
 *
 * 문자열은 아직 하드코딩이다 — 커맨드 표시명 키는 어느 티켓도 정의하지 않았고, 없는 키를 여기서
 * 만들면 `i18n` 네임스페이스 소유가 흐려진다. 표시명 i18n 은 후속 티켓 소관이다.
 */
fun registerAppCommands(registry: CommandRegistry, handlers: AppCommandHandlers) {
    registry.register(
        Command(
            id = CommandId("palette.open"),
            title = "커맨드 팔레트 열기",
            shortcut = OPEN_COMMAND_PALETTE_SHORTCUT,
            action = handlers.onOpenPalette,
        ),
    )
    registry.register(
        Command(
            id = CommandId("repository.close"),
            title = "저장소 닫기",
            shortcut = Shortcut(Key.W, setOf(ShortcutModifier.PRIMARY)),
            action = handlers.onCloseRepository,
        ),
    )
    registry.register(
        Command(
            id = CommandId("refs.refresh"),
            title = "참조 목록 새로 읽기",
            shortcut = Shortcut(Key.R, setOf(ShortcutModifier.PRIMARY)),
            action = handlers.onRefreshRefs,
        ),
    )
    // 계획을 여는 것부터 막는다 — 열면 그 화면이 읽고 적용하는 대상이 **직전 저장소**가 된다.
    registry.register(
        Command(
            id = CommandId("rebase.openPlan"),
            title = "리베이스 계획 열기",
            shortcut = Shortcut(Key.I, setOf(ShortcutModifier.PRIMARY, ShortcutModifier.SHIFT)),
            action = handlers.onOpenRebasePlan,
        ).blockedBy(handlers.repositoryChangeBlockedReason),
    )
    registry.register(
        Command(
            id = CommandId("diff.toggleView"),
            title = "Diff 보기 전환 (통합 ↔ 분할)",
            shortcut = Shortcut(Key.D, setOf(ShortcutModifier.PRIMARY)),
            action = handlers.onToggleDiffView,
        ),
    )
}

/**
 * 2차 기능의 명령을 같은 레지스트리에 얹는다.
 *
 * **등록 지점을 늘리지 않는다** — 화면 티켓은 명령의 정의와 콜백만 만들고(결정 E4) 등록은 여기
 * 한 곳에서 한다. 단축키 충돌은 등록 시점에 예외로 드러나므로, 등록을 흩으면 그 화면이 처음 열릴
 * 때까지 충돌이 숨는다.
 *
 * 이동 명령은 [AppDestination] 목록에서 **유도한다**. 목록에 화면을 더하면 명령이 따라 생기고,
 * 손으로 적은 목록이 뒤처져 생기는 "열 수 없는 화면" 이 없다.
 *
 * @param handlers 명령이 부를 동작. 저장소가 필요한 화면의 가용성 판정도 여기서 받는다.
 * @param graphCallbacks 그래프 조작 명령이 실행할 콜백 (UND-42 가 정의한 다섯 명령).
 * @param selectedGraphOperation 지금 선택으로 만들 수 있는 그래프 조작 하나. 없으면 다섯 명령 모두
 *   막힌 상태로 보인다 — 목록에서 숨기지 않는 이유는 사용자가 왜 못 쓰는지 알아야 하기 때문이다.
 */
fun registerSecondaryCommands(
    registry: CommandRegistry,
    handlers: SecondaryCommandHandlers,
    graphCallbacks: GraphOperationCallbacks,
    selectedGraphOperation: () -> GraphOperation?,
) {
    AppDestination.entries.forEach { destination ->
        registry.register(
            Command(
                id = CommandId("navigate.${destination.commandKey}"),
                title = "${destination.label} 열기",
                availability = { handlers.availabilityOf(destination) },
                action = { handlers.onNavigate(destination) },
            ),
        )
    }
    registry.register(
        Command(
            id = CommandId("repository.open"),
            title = "저장소 열기",
            shortcut = Shortcut(Key.O, setOf(ShortcutModifier.PRIMARY)),
            action = handlers.onOpenRepository,
        ),
    )
    registry.register(
        Command(
            id = CommandId("undo.last"),
            title = "되돌리기",
            shortcut = Shortcut(Key.Z, setOf(ShortcutModifier.PRIMARY)),
            action = handlers.onUndoLast,
        ).blockedBy(handlers.repositoryChangeBlockedReason),
    )
    graphOperationCommands(graphCallbacks, selectedOperation = selectedGraphOperation)
        .map { command -> command.blockedBy(handlers.repositoryChangeBlockedReason) }
        .forEach(registry::register)
}

/**
 * 저장소를 바꾸는 명령에 차단 사유를 얹는다 (결정 G43, UND-83).
 *
 * **새 표면을 만들지 않는다** — 이미 있는 [CommandAvailability.Blocked] 로 낸다. 사유가 없으면
 * 명령 자신의 판정을 그대로 쓴다: 게이트가 늘 이기면 "선택한 커밋이 없다" 같은 진짜 이유가
 * 이 문구에 가려진다.
 *
 * 감싸는 쪽이 아니라 **같은 계약의 새 명령**을 만든다 — [Command.execute] 가 조건을 먼저 보고
 * 통과한 것만 실행하므로, 막힌 명령의 [Command.action] 은 아예 불리지 않는다.
 */
private fun Command.blockedBy(reason: () -> String?): Command =
    Command(
        id = id,
        title = title,
        shortcut = shortcut,
        availability = { reason()?.let(CommandAvailability::Blocked) ?: availability() },
        action = action,
    )

/**
 * 2차 명령이 부를 동작. [AppCommandHandlers] 와 나누어 두는 이유는 소유가 다르기 때문이다 —
 * 이쪽은 화면 이동과 세션 전체에 걸린 동작이고, 저쪽은 열린 저장소 화면 안의 동작이다.
 */
class SecondaryCommandHandlers(
    val onNavigate: (AppDestination) -> Unit,
    val onOpenRepository: () -> Unit,
    val onUndoLast: () -> Unit,
    val availabilityOf: (AppDestination) -> CommandAvailability,
    /** [AppCommandHandlers.repositoryChangeBlockedReason] 과 같은 판정. 되돌리기·그래프 조작에 얹는다. */
    val repositoryChangeBlockedReason: () -> String?,
)

/**
 * 저장소 디렉터리 선택 대화상자. 창 소유자의 몫이라 여기 둔다 (`WelcomeEvents` KDoc).
 *
 * AWT `FileDialog` 를 쓰는 이유는 macOS 에서 네이티브 디렉터리 선택을 그대로 얻기 위해서다 —
 * `apple.awt.fileDialogForDirectories` 를 켜면 파일 대신 디렉터리를 고른다. 취소하면 `null` 이다.
 */
fun chooseDirectory(): RepositoryPath? {
    val previous = System.getProperty(MAC_DIRECTORY_DIALOG_KEY)
    System.setProperty(MAC_DIRECTORY_DIALOG_KEY, "true")
    return try {
        val dialog = FileDialog(null as Frame?, "저장소 폴더 선택", FileDialog.LOAD)
        dialog.isVisible = true
        dialog.directory?.let { directory ->
            dialog.file?.let { file -> RepositoryPath(java.io.File(directory, file).path) }
        }
    } finally {
        // 앱 전역 속성이라 원래 값으로 되돌린다 — 이후 파일 선택이 디렉터리 모드로 열리지 않게 한다.
        if (previous == null) {
            System.clearProperty(MAC_DIRECTORY_DIALOG_KEY)
        } else {
            System.setProperty(MAC_DIRECTORY_DIALOG_KEY, previous)
        }
    }
}

private const val MAC_DIRECTORY_DIALOG_KEY = "apple.awt.fileDialogForDirectories"
