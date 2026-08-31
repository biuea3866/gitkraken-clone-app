package dev.undine.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
/**
 * OS 메뉴바. 구조는 [APP_MENUS] 가 소유하고 여기서는 그 목록을 그린다.
 */
@Composable
internal fun FrameWindowScope.AppMenuBar(
    navigation: AppNavigationState,
    repositoryOpen: Boolean,
    onOpenRepository: () -> Unit,
    onUndoLast: () -> Unit,
) {
    MenuBar {
        APP_MENUS.forEach { menu ->
            Menu(text = menu.label) {
                menu.items.forEach { item ->
                    when (val command = item.command) {
                        is AppMenuCommand.Navigate -> Item(
                            text = item.label,
                            enabled = !command.destination.requiresRepository || repositoryOpen,
                            onClick = { navigation.go(command.destination) },
                        )

                        AppMenuCommand.OpenRepository -> Item(text = item.label, onClick = onOpenRepository)

                        AppMenuCommand.UndoLast -> Item(text = item.label, onClick = onUndoLast)
                    }
                }
            }
        }
    }
}
