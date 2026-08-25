package dev.undine.domain.identity

import dev.undine.domain.IdentityProfile
import dev.undine.domain.UndineException

/**
 * 신원 프로필 보관과 **저장소 로컬** Git 설정 적용 계약. 구현은 `IdentityGatewayImpl` 이다.
 *
 * 프로필 목록은 앱 설정에, 저장소 ↔ 프로필 연결은 그 저장소의 로컬 Git 설정에 둔다.
 * **전역 Git 설정은 어떤 연산도 건드리지 않는다** — 앱이 전역을 바꾸면 터미널 작업까지 영향을 받는다.
 */
interface IdentityGateway {

    /** 저장된 프로필 전체. 한 건도 없으면 빈 목록이다(정상 상태다). */
    suspend fun profiles(): List<IdentityProfile>

    /**
     * 프로필을 추가한다. 식별자는 [IdentityProfile.name] 이다.
     *
     * @throws UndineException.StateViolation 같은 이름의 프로필이 이미 있을 때
     */
    suspend fun saveProfile(profile: IdentityProfile)

    /**
     * 프로필 목록에서 [name] 을 지운다. **저장소를 훑지 않는다** — 어떤 저장소가 그 프로필을
     * 쓰는지 알려면 저장소 인덱스가 필요하고, 그것은 이 계약의 범위가 아니다. 사라진 이름을
     * 가리키는 저장소는 [IdentityWarning.ProfileNotAssigned] 로 다뤄진다.
     */
    suspend fun deleteProfile(name: String)

    /**
     * 현재 열린 저장소의 **로컬** 설정에 프로필을 반영한다
     * (`user.name`·`user.email`·`user.signingkey`·`undine.identityProfile`).
     *
     * [IdentityProfile.signingKeyId] 가 없으면 로컬 `user.signingkey` 를 **지운다** — 앞 프로필의
     * 키가 남아 있으면 엉뚱한 키로 서명하게 된다. 지우면 전역 설정을 따른다.
     */
    suspend fun applyProfile(profile: IdentityProfile)

    /**
     * 현재 열린 저장소의 로컬 신원 설정을 제거해 전역 설정을 따르게 한다 — 프로필 적용의 롤백이다.
     * 적용 전 값을 되살리지 않는다(그런 스냅샷을 어딘가 보관하지 않는다).
     */
    suspend fun clearLocalIdentity()

    /** 현재 열린 저장소에 지정된 프로필 **이름**. 지정된 적이 없으면 null 이다. */
    suspend fun assignedProfileName(): String?

    /**
     * 비교 기준이 되는 원격의 호스트. `origin` 을 우선하고, 없으면 첫 번째 원격을 본다.
     *
     * 원격이 없거나 URL 에서 호스트를 뽑을 수 없으면 null 이다 — 호스트 경고를 건너뛸 근거이지
     * 실패가 아니다.
     */
    suspend fun remoteHost(): String?
}
