package dev.undine.presentation.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * 팔레트와 단축키가 **공유하는 실행 경로**이자 세션 범위 최근 실행 이력.
 *
 * 실행 경로를 여기 하나로 모으는 것이 요점이다 — 팔레트 행 클릭과 단축키 입력이 각자 실행하면
 * 한쪽 이력만 쌓여 "최근 실행이 앞선다" 가 입구에 따라 달라진다.
 * 그래서 [CommandPaletteState] 와 [ShortcutHandler] 는 **같은 인스턴스를 받아야 한다** —
 * 그 규약을 지키는 것은 [CommandCenter] 의 책임이고, 배선(UND-26)은 [CommandCenter] 만 만진다.
 *
 * **이력은 앱 세션 범위로만 기억한다** — 영속화하지 않는다 (wave 3 결정 §UND-22).
 * 동점이면 최근 실행이 앞이다.
 */
@Stable
class CommandSession {

    private val executedIds = mutableStateListOf<CommandId>()

    /** 최신순 실행 이력. 세션이 끝나면 사라진다. */
    val recentCommandIds: List<CommandId> get() = executedIds.toList()

    /**
     * 조건을 판정해 실행하고, **실행된 명령만** 이력 맨 앞으로 옮긴다.
     *
     * 조건 불충족([CommandOutcome.Blocked])·실행 실패([CommandOutcome.Failed])는 기록하지 않는다 —
     * 하지 못한 일을 최근 실행으로 올리면 다음 검색에서 막힌 명령이 위로 온다.
     */
    fun execute(command: Command): CommandOutcome =
        command.execute().also { outcome ->
            if (outcome is CommandOutcome.Executed) moveToFront(outcome.commandId)
        }

    private fun moveToFront(id: CommandId) {
        executedIds.remove(id)
        executedIds.add(0, id)
    }
}

/**
 * 컴포지션 수명 동안 유지되는 실행 경로. 영속화 대상이 아니다.
 *
 * 배선은 이것 대신 [rememberCommandCenter] 를 쓴다 — 세션 하나를 팔레트·단축키에 함께 물려 준다.
 * 이 함수는 세션 하나만 필요한 곳(테스트·단독 소비)을 위해 남긴다.
 */
@Composable
fun rememberCommandSession(): CommandSession = remember { CommandSession() }
