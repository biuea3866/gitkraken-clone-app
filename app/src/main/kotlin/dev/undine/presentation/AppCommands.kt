package dev.undine.presentation

import androidx.compose.ui.input.key.Key
import dev.undine.presentation.palette.Command
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
fun registerAppCommands(
    registry: CommandRegistry,
    onOpenPalette: () -> Unit,
    onCloseRepository: () -> Unit,
    onRefreshRefs: () -> Unit,
    onToggleDiffView: () -> Unit,
) {
    registry.register(
        Command(
            id = CommandId("palette.open"),
            title = "커맨드 팔레트 열기",
            shortcut = OPEN_COMMAND_PALETTE_SHORTCUT,
            action = onOpenPalette,
        ),
    )
    registry.register(
        Command(
            id = CommandId("repository.close"),
            title = "저장소 닫기",
            shortcut = Shortcut(Key.W, setOf(ShortcutModifier.PRIMARY)),
            action = onCloseRepository,
        ),
    )
    registry.register(
        Command(
            id = CommandId("refs.refresh"),
            title = "참조 목록 새로 읽기",
            shortcut = Shortcut(Key.R, setOf(ShortcutModifier.PRIMARY)),
            action = onRefreshRefs,
        ),
    )
    registry.register(
        Command(
            id = CommandId("diff.toggleView"),
            title = "Diff 보기 전환 (통합 ↔ 분할)",
            shortcut = Shortcut(Key.D, setOf(ShortcutModifier.PRIMARY)),
            action = onToggleDiffView,
        ),
    )
}

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
