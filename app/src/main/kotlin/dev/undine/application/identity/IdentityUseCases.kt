package dev.undine.application.identity

import dev.undine.domain.IdentityProfile
import dev.undine.domain.UndineException
import dev.undine.domain.identity.IdentityGateway
import dev.undine.domain.identity.IdentityService
import dev.undine.domain.identity.IdentityWarning

/**
 * 저장된 신원 프로필 목록. 한 건도 없으면 빈 목록이다 — 설정 화면이 "아직 없음" 으로 표시한다.
 */
class LoadProfilesUseCase(private val identityService: IdentityService) {

    suspend fun execute(): List<IdentityProfile> = identityService.profiles()
}

/**
 * 프로필을 추가한다. 이름이 식별자라 같은 이름은 거부된다 — 거부를 결과로 뭉개지 않고 그대로 올린다.
 *
 * @throws UndineException.StateViolation 같은 이름의 프로필이 이미 있을 때
 */
class SaveProfileUseCase(private val identityService: IdentityService) {

    suspend fun execute(profile: IdentityProfile) = identityService.saveProfile(profile)
}

/**
 * 프로필을 목록에서 지운다. 저장소들의 로컬 설정은 건드리지 않는다 — 로컬 설정 제거는
 * 열려 있는 저장소를 대상으로 하는 [ClearLocalIdentityUseCase] 의 몫이다.
 */
class DeleteProfileUseCase(private val identityService: IdentityService) {

    suspend fun execute(name: String) = identityService.deleteProfile(name)
}

/** 열려 있는 저장소의 **로컬** Git 설정에 프로필을 적용한다. 전역 설정은 바뀌지 않는다. */
/**
 * 현재 저장소에 적용된 프로필 이름을 읽는다. 매핑이 없으면 `null`.
 *
 * presentation 은 Gateway 를 직접 부르지 않으므로 진입점이 필요하다 (결정 G16).
 */
class AssignedProfileNameUseCase(private val identityGateway: IdentityGateway) {

    suspend fun execute(): String? = identityGateway.assignedProfileName()
}

class ApplyProfileUseCase(private val identityService: IdentityService) {

    suspend fun execute(profile: IdentityProfile) = identityService.applyProfile(profile)
}

/** 열려 있는 저장소의 로컬 신원 설정을 제거해 전역 설정을 따르게 한다 — 프로필 적용의 롤백이다. */
class ClearLocalIdentityUseCase(private val identityService: IdentityService) {

    suspend fun execute() = identityService.clearLocalIdentity()
}

/** 커밋 직전 확인. 이상이 없으면 빈 목록이고, 경고는 가공 없이 그대로 전한다. */
class CheckIdentityBeforeCommitUseCase(private val identityService: IdentityService) {

    suspend fun execute(): List<IdentityWarning> = identityService.checkBeforeCommit()
}

/**
 * 신원 UseCase 묶음. 환경설정 계정 탭이 필요한 다섯 동작을 **한 의존성으로** 전달한다.
 *
 * 묶는 이유는 시그니처를 고정하기 위해서다 — 탭이 쓰는 동작이 늘 때마다 `PreferencesScreen` 의
 * 호출부까지 바뀌면, 그 파일을 수정할 수 없는 탭 티켓이 자기 일을 할 수 없다. 묶음 안을 늘리는 것은
 * 계정 탭 티켓의 자기 파일 변경으로 끝난다. 형태는 `RecoveryBisectUseCases` 와 같다.
 */
data class IdentityUseCases(
    val loadProfiles: LoadProfilesUseCase,
    val saveProfile: SaveProfileUseCase,
    val deleteProfile: DeleteProfileUseCase,
    val applyProfile: ApplyProfileUseCase,
    val clearLocalIdentity: ClearLocalIdentityUseCase,
    val assignedProfileName: AssignedProfileNameUseCase,
)
