package dev.undine.presentation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.presentation.palette.CommandAvailability

/**
 * 이 배선이 **실제로 닿게 한 화면**의 닫힌 목록.
 *
 * 메뉴·팔레트 명령·화면 렌더링이 모두 이 enum 하나에서 나온다. 목록과 배선을 따로 두면 한쪽에만
 * 있는 화면이 생기는데, 그 화면은 아무도 열 수 없는 채로 조용히 남는다 — 그래서 목록이 곧 계약이다.
 *
 * 배선되지 않은 기능(patch·자동 업데이트)은 여기에 없다. 눌러도 아무 일이 없는 항목은 없는 것보다
 * 나쁘다 (결정 G22).
 *
 * @property requiresRepository 열린 저장소가 있어야 의미가 있는 화면인가. 설정만 저장소 없이도
 *   볼 것이 있다 — 앱 설정 파일이 그 화면의 데이터를 소유한다. Undo 이력은 **저장소 세션이**
 *   소유하므로 (결정 G24) 저장소가 없으면 보여줄 이력 자체가 없다.
 */
enum class AppDestination(val label: String, val requiresRepository: Boolean) {
    WELCOME("시작 화면", requiresRepository = false),
    REPOSITORY("저장소", requiresRepository = true),
    PREFERENCES("설정", requiresRepository = false),
    BLAME("Blame", requiresRepository = true),
    UNDO("Undo 이력", requiresRepository = true),
    SUBMODULE_WORKTREE("Submodule / Worktree", requiresRepository = true),
    RECOVERY("Reflog / Bisect", requiresRepository = true),
    ;

    /** 팔레트 명령 id 의 뒷부분. enum 이름을 그대로 쓰면 저장된 오버라이드가 리네임에 끌려간다. */
    val commandKey: String get() = name.lowercase().replace("_", ".")
}

/**
 * 화면 테스트가 **지금 그려진 목적지**를 집는 태그. 배선이 고른 화면이 곧 이 값이라, 화면을 넘겨
 * 보는 테스트가 목적지 분기를 화면 구현의 문구에 기대지 않고 확인할 수 있다.
 */
internal object AppDestinationTags {
    fun of(destination: AppDestination): String = "destination.${destination.commandKey}"
}

/** 지금 어느 화면을 보고 있는가. 선택 상태(저장소·커밋·파일)와 달리 화면 전환만 담는다. */
@Stable
class AppNavigationState(initial: AppDestination = AppDestination.REPOSITORY) {

    var destination: AppDestination by mutableStateOf(initial)
        private set

    fun go(target: AppDestination) {
        destination = target
    }
}

/** 메뉴 항목이 하는 일. 배선이 닿게 한 것만 있다. */
sealed interface AppMenuCommand {

    /** 그 화면으로 이동한다. */
    data class Navigate(val destination: AppDestination) : AppMenuCommand

    /** 디렉터리를 골라 활성 저장소로 연다. */
    data object OpenRepository : AppMenuCommand

    /** Undo 스택 최상단 한 항목을 되돌린다. */
    data object UndoLast : AppMenuCommand
}

data class AppMenuItem(val label: String, val command: AppMenuCommand)

data class AppMenu(val label: String, val items: List<AppMenuItem>)

/**
 * OS 메뉴바의 정보 구조 (결정 G22).
 *
 * **팔레트와 1:1로 맞추지 않는다.** 메뉴바는 발견 경로이고 팔레트가 실행 경로다 — 둘을 같게 만들면
 * 메뉴가 수십 개가 되어 아무것도 찾을 수 없다. 여기에는 이번 배선이 닿게 한 것만 넣는다.
 */
val APP_MENUS: List<AppMenu> = listOf(
    AppMenu(
        label = "저장소",
        items = listOf(
            AppMenuItem("열기…", AppMenuCommand.OpenRepository),
            AppMenuItem("클론…", AppMenuCommand.Navigate(AppDestination.WELCOME)),
        ),
    ),
    AppMenu(
        label = "보기",
        items = listOf(
            AppMenuItem("설정", AppMenuCommand.Navigate(AppDestination.PREFERENCES)),
            AppMenuItem("Blame", AppMenuCommand.Navigate(AppDestination.BLAME)),
            AppMenuItem("Undo 이력", AppMenuCommand.Navigate(AppDestination.UNDO)),
            AppMenuItem(
                label = "Submodule / Worktree",
                command = AppMenuCommand.Navigate(AppDestination.SUBMODULE_WORKTREE),
            ),
            AppMenuItem("Reflog / Bisect", AppMenuCommand.Navigate(AppDestination.RECOVERY)),
        ),
    ),
    AppMenu(label = "편집", items = listOf(AppMenuItem("되돌리기", AppMenuCommand.UndoLast))),
    AppMenu(
        label = "도구",
        items = listOf(AppMenuItem("설정 열기", AppMenuCommand.Navigate(AppDestination.PREFERENCES))),
    ),
)

/**
 * 메뉴에서 닿을 수 있는 화면.
 */
fun reachableDestinations(menus: List<AppMenu> = APP_MENUS): Set<AppDestination> =
    menus.asSequence()
        .flatMap { menu -> menu.items.asSequence() }
        .mapNotNull { item ->
            when (val command = item.command) {
                is AppMenuCommand.Navigate -> command.destination
                AppMenuCommand.OpenRepository -> AppDestination.REPOSITORY
                AppMenuCommand.UndoLast -> null
            }
        }
        .toSet()

/**
 * 배선된 화면 전부에 메뉴가 닿는지 **앱 시작 시** 확인한다.
 *
 * 닿지 않는 화면은 조용히 남는다 — 코드에는 있지만 사용자는 영영 열 수 없고, 그 사실은 아무 데도
 * 드러나지 않는다. 그래서 빠짐을 경고가 아니라 **기동 실패**로 만든다.
 *
 * @throws IllegalStateException 메뉴가 닿지 못하는 화면이 있을 때
 */
fun verifyMenuReachesEveryDestination(menus: List<AppMenu> = APP_MENUS) {
    val unreachable = AppDestination.entries - reachableDestinations(menus)
    check(unreachable.isEmpty()) {
        "메뉴에서 닿을 수 없는 화면이 있습니다: ${unreachable.joinToString { it.name }}"
    }
}

/**
 * 실제로 그릴 화면. 저장소가 필요한 화면인데 저장소가 없으면 시작 화면으로 되돌린다 —
 * 저장소를 닫은 순간 열려 있던 Blame 화면이 빈 채로 남지 않게 한다.
 */
internal fun destinationFor(requested: AppDestination, repositoryOpen: Boolean): AppDestination = when {
    repositoryOpen -> requested
    requested.requiresRepository -> AppDestination.WELCOME
    else -> requested
}

/**
 * 저장소가 필요한 화면을 저장소 없이 열지 않는다. 열어 두면 빈 화면이 그려지고 사용자는 자기가
 * 무엇을 잘못했는지 알 수 없다.
 */
internal fun availabilityOf(destination: AppDestination, repositoryOpen: Boolean): CommandAvailability =
    if (destination.requiresRepository && !repositoryOpen) {
        CommandAvailability.Blocked("저장소를 먼저 여세요")
    } else {
        CommandAvailability.Available
    }
