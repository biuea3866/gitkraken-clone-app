package dev.undine.presentation.welcome

import androidx.compose.runtime.Immutable
import dev.undine.domain.RepositoryPath

/**
 * [WelcomeScreen] 이 올려 보내는 사용자 동작. 기본값이 전부 no-op 이라 배선 전에도 화면을 띄워볼 수 있다.
 *
 * `data class` 로 두지 않는다 — 람다의 동등성 비교는 의미가 없어 값 객체가 아니다 (kotlin-idioms 3항).
 *
 * @property onChooseLocalDirectory 디렉터리 선택 대화상자 열기. 파일 선택 UI 는 창 소유자(UND-26)가
 *   붙인다 — 화면은 "고르고 싶다"만 알린다.
 * @property clone clone 섹션의 동작 묶음.
 */
@Immutable
class WelcomeEvents(
    val onOpenRecent: (RepositoryPath) -> Unit = {},
    val onForgetRecent: (RepositoryPath) -> Unit = {},
    val onChooseLocalDirectory: () -> Unit = {},
    val clone: WelcomeCloneEvents = WelcomeCloneEvents(),
    val onDismissNotice: () -> Unit = {},
)

/**
 * clone 섹션이 올려 보내는 동작. 따로 묶은 이유는 clone 섹션이 자기와 무관한 콜백을 받지 않게 하려는 것이다.
 *
 * @property onUrlChange 원격 주소 입력이 바뀌었다. 값의 소유자는 [WelcomeScreenState] 다.
 * @property onTargetChange 대상 디렉터리 경로 입력이 바뀌었다.
 * @property onStart `(원격 URL, 대상 디렉터리 경로)`.
 */
@Immutable
class WelcomeCloneEvents(
    val onUrlChange: (String) -> Unit = {},
    val onTargetChange: (String) -> Unit = {},
    val onStart: (String, String) -> Unit = { _, _ -> },
    val onCancel: () -> Unit = {},
)
