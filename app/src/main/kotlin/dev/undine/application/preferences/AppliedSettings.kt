package dev.undine.application.preferences

import dev.undine.domain.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **지금 적용된 설정 한 곳.** 시작 시 읽은 값과 갱신으로 바뀐 값이 모두 여기로 모인다 —
 * 두 경로가 따로 놀면 어느 쪽이 진짜인지 갈린다.
 *
 * 관측 수단은 `StateFlow` 다. Compose 가 `collectAsState` 로 그대로 구독하고, 대안인
 * `mutableStateOf` 는 **application 레이어에 Compose 를 끌어들인다.**
 *
 * 여기에는 [Settings] 만 둔다 — 저장된 언어 태그를 어느 카탈로그로 그릴지 같은 **표현 계층의
 * 판단**은 `presentation/i18n` 이 소유한다.
 */
class AppliedSettings {

    /** 값을 만드는 일과 발행을 함께 감싼다 — 커밋 순서와 발행 순서를 구조로 같게 만든다. */
    private val serialization = Mutex()

    private val state = MutableStateFlow<Settings?>(null)

    /** 아직 아무것도 읽지 못했으면 `null` 이다. 배선은 그때 첫 프레임 기본값을 쓴다. */
    val current: StateFlow<Settings?> = state.asStateFlow()

    /**
     * [commit] 이 돌려준 설정을 적용값으로 발행한다.
     *
     * **[commit] 과 발행이 한 임계구역 안에 있다.** 저장은 저장소 Gateway 가 직렬화하지만 발행은
     * 그 밖이라, 두 갱신이 겹치면 커밋 순서와 발행 순서가 어긋나 적용값이 이전 설정으로 되돌아간다.
     * 시작 읽기도 같은 경로를 지나므로 늦게 끝난 읽기가 새 갱신값을 덮지 않는다.
     *
     * [commit] 이 예외를 던지면 **발행하지 않는다** — 저장되지 않은 값을 화면에 적용하면 재기동
     * 후 되돌아가 사용자가 이유를 알 수 없다.
     */
    suspend fun publish(commit: suspend () -> Settings): Settings =
        serialization.withLock { commit().also { state.value = it } }
}
