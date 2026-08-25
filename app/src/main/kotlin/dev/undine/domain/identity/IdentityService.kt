package dev.undine.domain.identity

import dev.undine.domain.Commit
import dev.undine.domain.HistoryGateway
import dev.undine.domain.IdentityProfile
import dev.undine.domain.RefName
import dev.undine.domain.UndineException

/**
 * 커밋 전 이메일 검사가 훑는 이력의 상한.
 *
 * 전 이력을 훑으면 큰 저장소에서 커밋할 때마다 멈춘다 — 확인 기능이 방해가 되면 사용자가 끈다.
 */
private const val RECENT_COMMIT_LIMIT = 50

private val HEAD = RefName("HEAD")

/**
 * 신원 프로필 관리와 커밋 전 검사 규칙.
 *
 * 프로필 보관·적용은 [IdentityGateway] 에 위임하고, **무엇을 경고할지** 판단만 여기서 한다.
 * 판단할 수 없는 항목(예상 호스트 없음·원격 없음·파싱 불가·이력 없음)은 경고를 만들지 않는다 —
 * 경고 기능이 실패 경로가 되면 안 된다.
 */
class IdentityService(
    private val identityGateway: IdentityGateway,
    private val historyGateway: HistoryGateway,
) {

    suspend fun profiles(): List<IdentityProfile> = identityGateway.profiles()

    /** @throws UndineException.StateViolation 같은 이름의 프로필이 이미 있을 때 */
    suspend fun saveProfile(profile: IdentityProfile) = identityGateway.saveProfile(profile)

    suspend fun deleteProfile(name: String) = identityGateway.deleteProfile(name)

    suspend fun applyProfile(profile: IdentityProfile) = identityGateway.applyProfile(profile)

    suspend fun clearLocalIdentity() = identityGateway.clearLocalIdentity()

    /**
     * 커밋하기 전에 알릴 문제를 모은다. 이상이 없으면 빈 목록이다.
     *
     * 프로필이 정해지지 않았으면 **거기서 끝낸다** — 비교 기준이 없는데 호스트·이메일을 따지면
     * 무엇과 비교했는지 알 수 없는 경고가 나온다.
     */
    suspend fun checkBeforeCommit(): List<IdentityWarning> {
        val profile = assignedProfile() ?: return listOf(IdentityWarning.ProfileNotAssigned)
        return listOfNotNull(hostWarning(profile), emailWarning(profile))
    }

    private suspend fun assignedProfile(): IdentityProfile? {
        val assignedName = identityGateway.assignedProfileName() ?: return null
        // 지워진 프로필을 가리키는 이름은 '미지정' 과 같게 다룬다 — 새 실패 상태를 만들지 않는다.
        return identityGateway.profiles().firstOrNull { profile -> profile.name == assignedName }
    }

    private suspend fun hostWarning(profile: IdentityProfile): IdentityWarning? {
        val expectedHost = profile.expectedHost?.let(::normalizedHostOf) ?: return null
        return identityGateway.remoteHost()
            ?.takeIf { remoteHost -> remoteHost != expectedHost }
            ?.let { remoteHost -> IdentityWarning.HostMismatch(expectedHost, remoteHost) }
    }

    private suspend fun emailWarning(profile: IdentityProfile): IdentityWarning? {
        val otherEmails = recentCommits()
            .map { commit -> commit.author.email }
            .filterNot { email -> email.equals(profile.email, ignoreCase = true) }
            .distinct()
        return IdentityWarning.EmailMismatch(profile.email, otherEmails).takeIf { otherEmails.isNotEmpty() }
    }

    private suspend fun recentCommits(): List<Commit> =
        try {
            historyGateway.load(listOf(HEAD), offset = 0, limit = RECENT_COMMIT_LIMIT)
        } catch (missingHead: UndineException.NotFound) {
            // 커밋이 하나도 없는 저장소는 HEAD 를 풀 수 없다 — 비교할 이력이 없다는 뜻이지 실패가 아니다.
            // 첫 커밋 직전이 이 검사가 가장 필요한 순간이라 여기서 멈추면 안 된다.
            if (missingHead.kind != UndineException.NotFound.Kind.REF) throw missingHead
            emptyList()
        }
}
