package dev.undine.presentation.palette

import kotlin.coroutines.cancellation.CancellationException

/** 명령 식별자. 팔레트 목록의 `key` 이자 최근 실행 이력의 단위다. */
@JvmInline
value class CommandId(val value: String) {

    init {
        require(value.isNotBlank()) { "명령 id 가 비어 있습니다" }
    }

    override fun toString(): String = value
}

/** 명령의 실행 조건 판정 결과. 막힌 이유는 사용자에게 그대로 보여줄 문구다. */
sealed interface CommandAvailability {
    data object Available : CommandAvailability
    data class Blocked(val reason: String) : CommandAvailability
}

/** 명령 실행 시도의 결과. 실행됨·조건 불충족·실행 중 실패를 구분한다. */
sealed interface CommandOutcome {
    val commandId: CommandId

    data class Executed(override val commandId: CommandId) : CommandOutcome
    data class Blocked(override val commandId: CommandId, val reason: String) : CommandOutcome
    data class Failed(override val commandId: CommandId, val cause: Throwable) : CommandOutcome
}

/**
 * 팔레트·단축키가 다루는 실행 단위.
 *
 * 명령은 **기능 티켓이 만들어 [CommandRegistry] 에 등록한다.** 이 티켓은 그릇만 제공하며
 * Gateway·UseCase 를 알지 못한다 — 필요한 조건과 동작은 [availability]·[action] 람다로 받는다.
 *
 * [action] 과 [availability] 를 `internal` 로 두는 것은 의도적이다. 호출부가 조건 판정을 건너뛰고
 * 동작만 실행하는 경로를 막는다 — 실행은 [execute] 하나로만 들어간다.
 *
 * @param availability 실행 조건. Compose 상태를 읽으면 팔레트가 열린 채로도 갱신된다.
 * @param shortcut 명령이 들고 오는 **기본 단축키**이며 불변이다. 사용자가 바꾼 단축키는 명령을
 *   다시 만들지 않고 [CommandRegistry.applyShortcutOverrides] 로 얹으므로, 지금 실제로 이 명령을
 *   부르는 값은 [CommandRegistry.effectiveShortcutOf] 가 답한다.
 */
class Command(
    val id: CommandId,
    val title: String,
    val shortcut: Shortcut? = null,
    internal val availability: () -> CommandAvailability = { CommandAvailability.Available },
    internal val action: () -> Unit,
)

/** 조건을 먼저 판정하고, 통과한 명령만 실행한다. */
internal fun Command.execute(): CommandOutcome =
    when (val state = availability()) {
        is CommandAvailability.Blocked -> CommandOutcome.Blocked(id, state.reason)
        CommandAvailability.Available -> runAction()
    }

/**
 * 명령 동작은 등록 티켓이 넘긴 임의의 람다라 어떤 예외든 나올 수 있다.
 * 팔레트 하나가 앱을 내리지 않도록 결과로 감싸되, **삼키지는 않는다** — 호출부가 사용자에게 알린다
 * (wave 3 결정 §UND-22: 실행 중 오류는 팔레트를 닫고 토스트로 알린다).
 */
@Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
private fun Command.runAction(): CommandOutcome =
    try {
        action()
        CommandOutcome.Executed(id)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        CommandOutcome.Failed(id, failure)
    }
