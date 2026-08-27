package dev.undine.presentation.preferences

import androidx.compose.runtime.Immutable
import dev.undine.application.externaltool.ExternalToolUseCases
import dev.undine.application.identity.IdentityUseCases
import dev.undine.presentation.palette.CommandRegistry

/**
 * 세 탭이 설정 저장 경로 **밖에서** 필요로 하는 것.
 *
 * 여섯 탭 중 일반·Git·고급은 [PreferencesState] 와 문구만으로 충분하다 — 그 탭의 값은 전부 앱
 * 설정이라 저장 경로가 곧 전부다. 나머지 셋은 설정 파일이 소유하지 않은 것을 다룬다:
 * 계정은 신원 서비스, 도구는 외부 프로세스, 단축키는 명령 레지스트리.
 *
 * **묶어서 넘기는 이유는 호출부를 고정하기 위해서다.** 탭 티켓은 `PreferencesScreen` 을 수정할 수
 * 없는데, 셋을 낱개로 넘기면 의존이 하나 늘 때마다 그 파일이 바뀐다. 묶음 안을 늘리는 것은 이
 * 파일 하나의 변경으로 끝나고, 배선(UND-51)은 묶음 하나만 만들면 된다.
 */
@Immutable
data class PreferencesTabDependencies(
    val identity: IdentityUseCases,
    val externalTools: ExternalToolUseCases,
    val commands: CommandRegistry,
)
