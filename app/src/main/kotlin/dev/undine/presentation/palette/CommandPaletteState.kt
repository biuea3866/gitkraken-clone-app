package dev.undine.presentation.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 팔레트 화면 상태 홀더 — 열림 여부와 검색어를 가진다 (compose-ui 규칙 1).
 *
 * 명령 목록 자체는 [CommandRegistry] 가 소유하고, 실행과 최근 실행 이력은 [CommandSession] 이 소유한다.
 * 이 홀더는 조회·정렬·열고 닫기만 다루며 Gateway·UseCase 를 알지 못한다.
 *
 * @param session 단축키 처리기와 **같은 인스턴스**여야 한다 — 그래야 어느 입구로 실행해도
 *   최근 실행 우선순위가 같게 적용된다. 그 규약은 [CommandCenter] 가 소유하므로
 *   팔레트 패키지 밖에서는 이 생성자를 쓰지 않고 [CommandCenter.paletteState] 를 받는다.
 */
@Stable
class CommandPaletteState internal constructor(
    private val registry: CommandRegistry,
    private val session: CommandSession,
) {

    private var openState by mutableStateOf(false)
    private var queryState by mutableStateOf("")

    val isOpen: Boolean get() = openState

    var query: String
        get() = queryState
        set(value) {
            queryState = value
        }

    /** 최신순 실행 이력. 팔레트·단축키 실행을 함께 담으며 세션이 끝나면 사라진다. */
    val recentCommandIds: List<CommandId> get() = session.recentCommandIds

    /** 등록된 명령이 하나라도 있는지 — 빈 상태 안내와 검색 결과 없음을 구분하는 기준이다. */
    val hasCommands: Boolean get() = registry.commands.isNotEmpty()

    /**
     * 지금 검색어에 맞는 후보. 각 후보의 실행 조건은 **읽는 시점에** 판정하므로,
     * 조건 람다가 Compose 상태를 읽으면 팔레트가 열린 채로도 활성/비활성이 갱신된다.
     */
    val candidates: List<CommandCandidate>
        get() = searchCommands(registry.commands, queryState, session.recentCommandIds).map { command ->
            CommandCandidate(
                command = command,
                availability = command.availability(),
                shortcutLabel = registry.shortcutLabelOf(command),
            )
        }

    fun open() {
        openState = true
    }

    /** 닫으면 검색어도 비운다 — 다음에 열 때 이전 검색어가 남아 있으면 후보가 가려진다. */
    fun close() {
        openState = false
        queryState = ""
    }

    /**
     * 조건을 만족하면 실행하고 팔레트를 닫는다.
     *
     * - 실행됨: 최근 목록 맨 앞으로 옮기고 닫는다.
     * - 조건 불충족: 실행하지 않고 **열린 채로 둔다** — 사유는 후보 행에 남는다.
     * - 실행 중 오류: 팔레트를 닫는다. 사용자에게 알리는 토스트는 호출부가 결과를 받아 띄운다.
     */
    fun execute(command: Command): CommandOutcome {
        val outcome = session.execute(command)
        when (outcome) {
            is CommandOutcome.Executed -> close()
            is CommandOutcome.Failed -> close()
            is CommandOutcome.Blocked -> Unit
        }
        return outcome
    }
}

/** 컴포지션 수명 동안 유지되는 팔레트 상태. 영속화 대상이 아니다. */
@Composable
fun rememberCommandPaletteState(
    registry: CommandRegistry,
    session: CommandSession,
): CommandPaletteState = remember(registry, session) { CommandPaletteState(registry, session) }
