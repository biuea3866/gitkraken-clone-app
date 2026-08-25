package dev.undine.infrastructure.identity

import dev.undine.domain.IdentityProfile
import dev.undine.domain.SettingsGateway
import dev.undine.domain.UndineException
import dev.undine.domain.identity.IdentityGateway
import dev.undine.domain.identity.normalizedHostOf
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.StoredConfig
import java.io.IOException

private const val OPERATION_APPLY = "identity.applyProfile"
private const val OPERATION_CLEAR = "identity.clearLocalIdentity"
private const val OPERATION_ASSIGNED = "identity.assignedProfileName"
private const val OPERATION_REMOTE_HOST = "identity.remoteHost"

/** 저장소 ↔ 프로필 연결을 적어 두는 자리. git 이 모르는 키라 다른 도구의 동작을 바꾸지 않는다. */
private const val SECTION_UNDINE = "undine"
private const val KEY_IDENTITY_PROFILE = "identityProfile"

private const val KEY_NAME = "name"
private const val KEY_EMAIL = "email"

/** git 이 쓰는 철자 그대로다 — `signingKey` 로 적으면 git 이 읽지 못한다. */
private const val KEY_SIGNING_KEY = "signingkey"

private const val ORIGIN = "origin"
private const val KEY_URL = "url"

/**
 * [IdentityGateway] 의 JGit·설정 파일 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — `Repository` 는 스레드 안전하지 않아
 * 직렬화와 `Dispatchers.IO` 전환을 그 경계가 책임진다. 이 클래스는 락도 디스패처도 다시 걸지 않는다.
 *
 * **쓰기는 저장소 로컬 config 파일 하나뿐이다.** `Repository.getConfig()` 는 전역·시스템 설정을
 * base 로 물고 있지만 `save()` 가 쓰는 파일은 `.git/config` 다 — 전역 설정은 어떤 경로에서도
 * 바뀌지 않는다.
 *
 * 프로필 목록 갱신은 [SettingsGateway.update] 에 맡긴다 — 읽기-수정-쓰기를 그 Gateway 가 자기
 * 임계구역 안에서 끝내므로, 저장·삭제가 교차해도 오래된 스냅샷이 방금 저장된 프로필이나 다른 설정
 * 값을 덮어쓰지 않는다. **이 클래스는 락을 갖지 않는다** — 동기화는 그 자원의 Gateway 소유이고,
 * 소비자가 락을 덧대면 중복이다(결정 A1·N1). 서명 키는 **ID 만** 오간다 — 키 본문·패스프레이즈는
 * 이 경로를 지나지 않는다.
 */
class IdentityGatewayImpl(
    private val gitAccess: GitAccess,
    private val settingsGateway: SettingsGateway,
) : IdentityGateway {

    override suspend fun profiles(): List<IdentityProfile> = settingsGateway.load().identityProfiles

    override suspend fun saveProfile(profile: IdentityProfile) = settingsGateway.update { settings ->
        if (settings.identityProfiles.any { saved -> saved.name == profile.name }) {
            throw UndineException.StateViolation("같은 이름의 신원 프로필이 이미 있습니다: '${profile.name}'")
        }
        settings.copy(identityProfiles = settings.identityProfiles + profile)
    }

    // 없는 이름을 지우면 목록이 그대로라 `update` 가 쓰지 않고 끝낸다.
    override suspend fun deleteProfile(name: String) = settingsGateway.update { settings ->
        settings.copy(
            identityProfiles = settings.identityProfiles.filterNot { saved -> saved.name == name },
        )
    }

    override suspend fun applyProfile(profile: IdentityProfile): Unit =
        configWrite(OPERATION_APPLY) { config ->
            config.setString(ConfigConstants.CONFIG_USER_SECTION, null, KEY_NAME, profile.name)
            config.setString(ConfigConstants.CONFIG_USER_SECTION, null, KEY_EMAIL, profile.email)
            // 서명 키가 없는 프로필인데 앞 프로필의 키가 남으면 엉뚱한 키로 서명한다.
            if (profile.signingKeyId == null) {
                config.unset(ConfigConstants.CONFIG_USER_SECTION, null, KEY_SIGNING_KEY)
            } else {
                config.setString(
                    ConfigConstants.CONFIG_USER_SECTION,
                    null,
                    KEY_SIGNING_KEY,
                    profile.signingKeyId,
                )
            }
            config.setString(SECTION_UNDINE, null, KEY_IDENTITY_PROFILE, profile.name)
        }

    override suspend fun clearLocalIdentity(): Unit =
        configWrite(OPERATION_CLEAR) { config ->
            config.unset(ConfigConstants.CONFIG_USER_SECTION, null, KEY_NAME)
            config.unset(ConfigConstants.CONFIG_USER_SECTION, null, KEY_EMAIL)
            config.unset(ConfigConstants.CONFIG_USER_SECTION, null, KEY_SIGNING_KEY)
            config.unset(SECTION_UNDINE, null, KEY_IDENTITY_PROFILE)
        }

    override suspend fun assignedProfileName(): String? =
        configOperation(OPERATION_ASSIGNED) { config ->
            config.getString(SECTION_UNDINE, null, KEY_IDENTITY_PROFILE)?.takeIf { it.isNotBlank() }
        }

    /**
     * 원격 URL 은 여기서 직접 읽는다 — `RemoteGateway` 는 URL 을 노출하지 않고, 그 이유(토큰이
     * 섞일 수 있다)는 유지한다. 뽑아낸 호스트만 밖으로 나가고 URL 자체는 이 함수를 벗어나지 않는다.
     */
    override suspend fun remoteHost(): String? =
        configOperation(OPERATION_REMOTE_HOST) { config ->
            val remotes = config.getSubsections(ConfigConstants.CONFIG_REMOTE_SECTION).sorted()
            val remote = remotes.firstOrNull { name -> name == ORIGIN } ?: remotes.firstOrNull()
            remote?.let { name -> config.getString(ConfigConstants.CONFIG_REMOTE_SECTION, name, KEY_URL) }
                ?.let(::normalizedHostOf)
        }

    /**
     * 로컬 config 를 고쳐 쓴다. 쓰기에 실패하면 **메모리 상태를 호출 전으로 되돌린 뒤** 실패를 알린다 —
     * 공유 `Repository` 의 config 는 다음 호출도 같은 인스턴스를 보므로, 쓰지 못한 변경을 남기면
     * 후속 조회가 디스크에 없는 값을 읽는다. 복원까지 [GitAccess] 임계구역 안에서 끝낸다.
     *
     * 되돌리기는 디스크 재로드가 아니라 **직전 내용 스냅샷**으로 한다 — `load()` 는 파일이 그대로면
     * 다시 파싱하지 않아, 쓰지 못한 메모리 변경을 그대로 남긴다.
     */
    private suspend fun configWrite(operation: String, edit: (StoredConfig) -> Unit): Unit =
        configOperation(operation) { config ->
            val beforeEdit = config.toText()
            try {
                edit(config)
                config.save()
            } catch (failure: IOException) {
                config.restoreTo(beforeEdit, failure)
                throw failure
            }
        }

    private suspend fun <T> configOperation(operation: String, block: (StoredConfig) -> T): T =
        try {
            gitAccess.withRepository { repository: Repository -> block(repository.config) }
        } catch (failure: IOException) {
            // 설정 파일을 쓰지 못했는데 성공으로 알리면 사용자는 신원이 바뀐 줄 안다.
            throw UndineException.GitOperationFailed(operation, failure)
        }
}

/**
 * 메모리 상태를 [beforeEdit] 내용으로 되돌린다. 복원 자체가 실패하면 원인을 [failure] 에 매달아 둔다 —
 * 복원 실패로 원래 실패를 덮으면 화면이 무엇 때문에 실패했는지 말할 수 없다.
 */
private fun StoredConfig.restoreTo(beforeEdit: String, failure: IOException) {
    try {
        fromText(beforeEdit)
    } catch (restoreFailure: ConfigInvalidException) {
        failure.addSuppressed(restoreFailure)
    }
}
