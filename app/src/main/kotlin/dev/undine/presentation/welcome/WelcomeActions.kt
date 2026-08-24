package dev.undine.presentation.welcome

import dev.undine.application.welcome.CloneRepositoryUseCase
import dev.undine.application.welcome.ForgetRecentRepositoryUseCase
import dev.undine.application.welcome.LoadRecentRepositoriesUseCase
import dev.undine.application.welcome.OpenRepositoryUseCase

/**
 * Welcome 화면이 쓰는 **application 경계 전부**. 조립은 UND-26 이 하고 여기서는 받기만 한다.
 *
 * 네 UseCase 를 한 값으로 묶는 이유는 두 가지다 — presentation 이 Gateway 를 직접 주입받지 않는다는
 * 경계를 한눈에 보이게 하고([[architecture-layers]]), 작업이 늘어도 [WelcomeState] 생성자가
 * 길어지지 않게 한다.
 */
class WelcomeActions(
    val loadRecentRepositories: LoadRecentRepositoriesUseCase,
    val openRepository: OpenRepositoryUseCase,
    val cloneRepository: CloneRepositoryUseCase,
    val forgetRecentRepository: ForgetRecentRepositoryUseCase,
)
