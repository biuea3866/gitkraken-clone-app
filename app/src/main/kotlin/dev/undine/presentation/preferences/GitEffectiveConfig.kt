package dev.undine.presentation.preferences

import androidx.compose.runtime.Immutable
import dev.undine.domain.UndineException
import dev.undine.domain.gitconfig.EffectiveValue
import dev.undine.domain.gitconfig.GitConfigKey

/**
 * git 실효값 조회의 상태 — **넷을 타입으로 가른다** (결정 G39).
 *
 * 값 맵 하나로 네 상태를 표현하면 "아직 안 읽음"·"읽는 중"·"읽었는데 없음"·"읽기 실패" 가 모두 빈
 * 맵으로 접히고, 화면은 그 넷을 구분할 방법이 없어 앱 설정 값을 실효값이라고 말하게 된다.
 * **APP 출처는 "Git 세 범위 어디에도 없음을 확인했다" 는 주장**이라, 확인하기 전에 그 말을 하면
 * 사용자는 `~/.gitconfig` 에 값이 있는데도 앱 설정이 쓰인다고 믿는다.
 *
 * 부재([Loaded] 의 빈 맵)와 실패([Failed])를 섞지 않는다 (결정 G35 UND-75 2).
 */
@Immutable
sealed interface GitEffectiveConfig {

    /** 지금까지 읽어 둔 실효값. 아직 아무것도 읽지 못했으면 비어 있다. */
    val values: Map<GitConfigKey, EffectiveValue>

    /**
     * 이 키의 실효값. `null` 은 **값을 모른다**는 뜻일 뿐 부재가 아니다 — 부재로 읽어도 되는 것은
     * [Loaded] 뿐이고, 그 판정은 상태를 함께 보는 호출부가 한다.
     */
    operator fun get(key: GitConfigKey): EffectiveValue? = values[key]

    /** 아직 조회를 시작하지 않았다 — 최초 렌더와 저장소 전환 직후. */
    @Immutable
    data object Unread : GitEffectiveConfig {
        override val values: Map<GitConfigKey, EffectiveValue> get() = emptyMap()
    }

    /**
     * 조회 중. 앞서 읽어 둔 값은 그대로 들고 있는다 — 다시 읽는 동안 화면을 비우면 사용자는
     * git 설정이 사라진 줄 안다.
     */
    @Immutable
    data class Loading(override val values: Map<GitConfigKey, EffectiveValue>) : GitEffectiveConfig

    /** 조회 성공. 빈 맵은 **"git 세 범위 모두에 값이 없음" 을 확인한 답**이다. */
    @Immutable
    data class Loaded(override val values: Map<GitConfigKey, EffectiveValue>) : GitEffectiveConfig

    /**
     * 조회 실패. 읽어 둔 값은 남기고 사유만 얹는다 — 실패를 빈 결과로 접으면 손상된 설정 파일이
     * 사용자에게 "git 에 설정 없음" 으로 보인다.
     */
    @Immutable
    data class Failed(
        override val values: Map<GitConfigKey, EffectiveValue>,
        val failure: UndineException,
    ) : GitEffectiveConfig
}
