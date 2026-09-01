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
     * [originalName] 프로필의 이메일·서명 키를 **한 번에** 바꾼다.
     *
     * `deleteProfile` + `saveProfile` 조합은 그 사이에 실패하면 프로필을 잃는다 — 데이터 유실
     * 경로다. 그래서 수정을 계약으로 올려 읽기-수정-쓰기를 한 임계구역 안에서 끝낸다.
     *
     * **이름은 바꾸지 않는다** — [IdentityProfile.name] 이 [originalName] 과 다르면 거부한다
     * (결정 G38). 저장소들은 로컬 설정에 프로필 **이름**을 적어 두므로 이름이 바뀌면 그 참조들이
     * 옛 이름을 가리킨 채 남는다. 이름 변경은 참조 이관을 포함한 별도 기능이지 이 연산이 아니다.
     *
     * @throws UndineException.StateViolation [originalName] 프로필이 없거나,
     * [IdentityProfile.name] 이 [originalName] 과 다를 때
     */
    suspend fun updateProfile(originalName: String, profile: IdentityProfile)

    /**
     * 프로필 목록에서 [name] 을 지운다. **저장소를 훑지 않는다** — 어떤 저장소가 그 프로필을
     * 쓰는지 알려면 저장소 인덱스가 필요하고, 그것은 이 계약의 범위가 아니다. 사라진 이름을
     * 가리키는 저장소는 [IdentityWarning.ProfileNotAssigned] 로 다뤄진다.
     */
    suspend fun deleteProfile(name: String)

    /**
     * [name] 프로필을 지우기 전에 알려야 하는 사용 현황 — 사용 저장소 수와 삭제 후 적용될
     * 전역 신원이다.
     *
     * 후보 집합은 `Settings.recentRepositories` 다. **디스크를 훑지 않고**, 같은 저장소가 여러 번
     * 들어갈 수 있는 열린 탭 목록도 쓰지 않는다 — 집계 단위는 탭이 아니라 저장소다.
     * 후보 목록이 비었거나 아무도 쓰지 않으면 `0` 이다.
     *
     * **집계는 실패로 끝나지 않는다** — 삭제 확인이 실패 경로가 되면 안 된다. 대신 읽지 못한 것을
     * "없음" 으로 접지 않고 그대로 알린다 (결정 G36): 확인하지 못한 저장소는
     * [IdentityProfileUsage.uncheckedRepositoryCount] 에, 전역 설정을 읽지 못한 것은
     * [GlobalIdentity.Unreadable] 로 구분해 돌려준다.
     */
    suspend fun profileUsage(name: String): IdentityProfileUsage

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
