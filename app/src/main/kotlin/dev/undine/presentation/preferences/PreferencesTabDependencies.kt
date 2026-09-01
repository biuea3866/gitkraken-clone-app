package dev.undine.presentation.preferences

import androidx.compose.runtime.Immutable
import dev.undine.application.diagnostics.DiagnosticsUseCases
import dev.undine.application.externaltool.ExternalToolUseCases
import dev.undine.application.gitconfig.ReadEffectiveConfigUseCase
import dev.undine.application.identity.IdentityUseCases
import dev.undine.application.typography.LoadMonospaceFontsUseCase
import dev.undine.domain.RepositoryPath
import dev.undine.presentation.palette.CommandRegistry

/**
 * 탭들이 설정 저장 경로 **밖에서** 필요로 하는 것.
 *
 * 일반 탭만 [PreferencesState] 와 문구로 충분하다 — 그 값은 전부 앱 설정이라 저장 경로가 곧
 * 전부다. 나머지는 설정 파일이 소유하지 않은 것을 다룬다: 계정은 신원 서비스, 도구는 외부
 * 프로세스와 설치된 서체, 단축키는 명령 레지스트리, Git 은 git 설정 실효값, 고급은 로그 디렉터리.
 *
 * **묶어서 넘기는 이유는 호출부를 고정하기 위해서다.** 탭 티켓은 `PreferencesScreen` 을 수정할 수
 * 없는데, 낱개로 넘기면 의존이 하나 늘 때마다 그 파일이 바뀐다. 묶음 안을 늘리는 것은 이
 * 파일 하나의 변경으로 끝나고, 배선은 묶음 하나만 만들면 된다.
 *
 * **전부 UseCase 다.** Gateway 를 여기에 담으면 탭이 domain 계약을 직접 부르게 되어 레이어 방향이
 * 깨진다 — 화면은 application 경계까지만 안다.
 *
 * @property repository git 실효값을 볼 때 저장소 범위를 함께 볼 대상. 열린 저장소가 없으면 `null`
 *   이고, 그때는 전역·시스템 범위만 본다 — 저장소 없이도 실효값을 말할 수 있어야 한다.
 */
@Immutable
data class PreferencesTabDependencies(
    val identity: IdentityUseCases,
    val externalTools: ExternalToolUseCases,
    val commands: CommandRegistry,
    val gitConfig: ReadEffectiveConfigUseCase,
    val monospaceFonts: LoadMonospaceFontsUseCase,
    val diagnostics: DiagnosticsUseCases,
    val repository: RepositoryPath?,
)
