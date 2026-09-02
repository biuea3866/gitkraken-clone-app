package dev.undine.presentation.shell

import androidx.compose.runtime.Immutable
import dev.undine.domain.RepositoryPath

/**
 * 활성 탭이 가리키는 저장소 — **조작 대상**과 **탭이 가리키는 대상**을 가른 presentation 상태.
 *
 * 하나의 nullable `RepositoryPath?` 로는 이 셋을 구분할 수 없다: "열린 저장소" · "조작 대상" ·
 * "화면을 그릴지". 경로를 잃은 탭(`TabAvailability.MissingPath`)에서는 셋이 갈라진다 —
 * 탭은 그 저장소를 가리키지만 열려 있는 핸들은 **직전 저장소**다. 그래서 어느 쪽을 골라도 하나가
 * 깨진다: 경로를 넘기면 사용자가 A 를 보는데 조작이 B 로 가고, `null` 을 넘기면 목적지가 시작
 * 화면으로 가 탭 막대까지 사라져 다른 탭을 고르거나 그 탭을 닫을 수 없다 (UND-81).
 *
 * **불리언 플래그를 덧붙이지 않는다.** `path` 옆에 `available` 을 두면 기존 `path != null` 검사가
 * 전부 "쓸 수 있다" 는 뜻으로 조용히 바뀌어, 막으려던 결함이 같은 자리에 다시 생긴다. 대신
 * 조작 대상은 [Operable] 을 분해해야만 꺼낼 수 있게 하고, 화면 판정이 보는 값은
 * [referencedPath] 로 따로 노출한다 — **타입이 오용을 막는다.**
 *
 * 이 세 갈래는 presentation 이 application 의 두 값(`TabAvailability.Available`/`MissingPath` 와
 * 활성 탭의 유무)에서 파생한다. 세션·Undo 소유 동작은 application 에 그대로 남는다.
 */
@Immutable
sealed interface ActiveRepository {

    /** 열린 탭이 없다. 조작 대상도 가리키는 저장소도 없다. */
    data object None : ActiveRepository

    /** 탭이 가리키는 저장소를 지금 열어 두었다 — **조작이 갈 수 있는 유일한 상태**다. */
    data class Operable(val path: RepositoryPath) : ActiveRepository

    /**
     * 탭은 이 저장소를 가리키지만 지금 그 핸들로 갈 수 없다 — 경로가 옮겨졌거나 지워졌다.
     *
     * 탭 자체는 사용자의 것이라 지우지 않고, 그 저장소의 Undo 이력도 버리지 않는다.
     * 경로가 돌아오면 같은 저장소로 [Operable] 이 되고 이력이 이어진다.
     */
    data class Unavailable(val path: RepositoryPath) : ActiveRepository

    /**
     * 탭이 **가리키는** 저장소. 화면 판정과 표시가 보는 값이며 **조작에 쓰지 않는다** —
     * 조작 대상은 [Operable] 을 분해해서만 얻는다 (`AppShellSelection.repository`).
     */
    val referencedPath: RepositoryPath?
        get() = when (this) {
            None -> null
            is Operable -> path
            is Unavailable -> path
        }
}
