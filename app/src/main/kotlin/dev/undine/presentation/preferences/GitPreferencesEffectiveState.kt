package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.gitconfig.ReadEffectiveConfigUseCase
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Git 탭이 앱 설정 위에 겹쳐 보여 줄 git 설정 실효값의 상태 홀더 (compose-ui 규칙 1).
 * [ReadEffectiveConfigUseCase] 만 호출하고 Gateway 를 알지 못한다.
 *
 * **부재와 실패를 섞지 않는다** (결정 G35 UND-75 2). 값이 없는 키는 [GitEffectiveConfig.Loaded] 의
 * 맵에 없고, 설정 파일을 읽지 못한 것은 [GitEffectiveConfig.Failed] 다 — 실패를 빈 맵으로 접으면
 * 손상된 설정이 "설정 안 함" 으로 보이고 화면은 앱 설정 값을 실효값이라고 말하게 된다.
 *
 * **아직 읽지 않은 것도 부재가 아니다** (결정 G39). 최초 상태는 [GitEffectiveConfig.Unread] 이고
 * 조회 중에는 [GitEffectiveConfig.Loading] 이라, 화면이 확인하지 않은 값을 앱 출처로 말할 수 없다.
 *
 * **읽지 못했다고 앞서 읽은 값을 지우지 않는다.** 실패한 조회로 맵을 비우면 사용자는 git 설정이
 * 사라진 줄 안다.
 *
 * @param repository 저장소 범위를 함께 볼 대상. 저장소가 열려 있지 않으면 `null` 이고, 그때는
 *   전역·시스템 값만 올라온다 — 실패가 아니다.
 */
@Stable
class GitPreferencesEffectiveState(
    private val scope: CoroutineScope,
    private val readEffectiveConfig: ReadEffectiveConfigUseCase,
    private val repository: RepositoryPath?,
) {
    /**
     * 지금 적용되는 git 설정 값과 **그 값을 어디까지 알아낸 상태인지**. 상태를 타입으로 갈라 두어
     * 소비자가 값 맵만 보고 미조회·조회 중·실패를 빠뜨릴 수 없게 한다 (결정 G39) — 그 구멍이 나면
     * 확인하지 않은 값이 앱 출처로 보이고, 손상된 설정이 "설정 안 함" 으로 보인다.
     */
    var effective: GitEffectiveConfig by mutableStateOf(GitEffectiveConfig.Unread)
        private set

    /** git 설정 실효값을 다시 읽는다. 탭 진입 시 배선이 호출한다. */
    fun refresh() {
        scope.launch { reload() }
    }

    private suspend fun reload() {
        // 읽는 중에도 읽어 둔 값은 들고 간다 — 비우면 사용자는 git 설정이 사라진 줄 안다.
        val known = effective.values
        effective = GitEffectiveConfig.Loading(known)
        effective = try {
            GitEffectiveConfig.Loaded(readEffectiveConfig.execute(repository))
        } catch (failure: UndineException) {
            GitEffectiveConfig.Failed(known, failure)
        }
    }
}

/**
 * 컴포지션 수명에 묶인 git 실효값 상태. 저장소가 바뀌면 새로 만들어 다시 읽는다 — 이전 저장소의
 * 값을 지금 저장소의 실효값이라고 말하지 않는다.
 */
@Composable
fun rememberGitPreferencesEffectiveState(
    gitConfig: ReadEffectiveConfigUseCase,
    repository: RepositoryPath?,
): GitPreferencesEffectiveState {
    val scope = rememberCoroutineScope()
    val state = remember(scope, gitConfig, repository) {
        GitPreferencesEffectiveState(scope, gitConfig, repository)
    }
    LaunchedEffect(state) { state.refresh() }
    return state
}
