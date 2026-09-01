package dev.undine.infrastructure.identity

import dev.undine.domain.IdentityProfile
import dev.undine.domain.Person
import dev.undine.domain.RepositoryPath
import dev.undine.domain.SettingsGateway
import dev.undine.domain.UndineException
import dev.undine.domain.identity.GlobalIdentity
import dev.undine.domain.identity.IdentityGateway
import dev.undine.domain.identity.IdentityProfileUsage
import dev.undine.domain.identity.normalizedHostOf
import dev.undine.infrastructure.git.repository.GitAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.IOException

private const val OPERATION_APPLY = "identity.applyProfile"
private const val OPERATION_CLEAR = "identity.clearLocalIdentity"
private const val OPERATION_ASSIGNED = "identity.assignedProfileName"
private const val OPERATION_REMOTE_HOST = "identity.remoteHost"

/** 저장소 ↔ 프로필 연결을 적어 두는 자리. git 이 모르는 키라 다른 도구의 동작을 바꾸지 않는다. */
private const val SECTION_UNDINE = "undine"
private const val KEY_IDENTITY_PROFILE = "identityProfile"

/** git 디렉터리 안의 로컬 설정 파일 이름. */
private const val CONFIG_FILE = "config"

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
    /**
     * 전역 Git 설정을 여는 방법. 기본값은 git 자신이 보는 사용자 전역 파일이다.
     *
     * 갈아 끼울 수 있게 둔 이유는 **테스트가 실제 사용자의 `~/.gitconfig` 에 의존하지 않게** 하기
     * 위해서다. 이 자리는 **읽기 전용**이다 — 어떤 연산도 전역 설정에 쓰지 않는다.
     */
    private val globalConfig: () -> StoredConfig = ::userGlobalConfig,
) : IdentityGateway {

    override suspend fun profiles(): List<IdentityProfile> = settingsGateway.load().identityProfiles

    override suspend fun saveProfile(profile: IdentityProfile) = settingsGateway.update { settings ->
        if (settings.identityProfiles.any { saved -> saved.name == profile.name }) {
            throw UndineException.StateViolation("같은 이름의 신원 프로필이 이미 있습니다: '${profile.name}'")
        }
        settings.copy(identityProfiles = settings.identityProfiles + profile)
    }

    /**
     * 찾기·교체를 **하나의 [SettingsGateway.update]** 안에서 끝낸다 — 그 Gateway 가 자기
     * 임계구역에서 읽기-수정-쓰기를 마치므로 이 연산은 원자적이다. 삭제 후 저장으로 나누면
     * 사이의 실패가 프로필을 지운 채로 남긴다.
     *
     * **이름은 바꾸지 못한다** (결정 G38). 저장소들은 로컬 설정의 `undine.identityProfile` 에
     * 프로필 **이름**을 적어 두므로, 이름을 바꾸면 그 참조들이 옛 이름을 가리킨 채 남는다 —
     * 프로필을 지운 것과 같은 결과인데 사용자는 "이름만 고쳤다" 고 생각한다. 이 연산이 없애려던
     * 유실이 다른 문으로 들어오는 셈이다. 이름 변경은 참조 이관을 포함한 별도 기능이다.
     *
     * 이름 검사는 설정 스냅샷이 필요 없으므로 임계구역 **밖**에서 먼저 거부한다.
     */
    override suspend fun updateProfile(originalName: String, profile: IdentityProfile) {
        if (profile.name != originalName) {
            throw UndineException.StateViolation(
                "신원 프로필의 이름은 수정으로 바꿀 수 없습니다: '$originalName' → '${profile.name}'",
            )
        }
        settingsGateway.update { settings ->
            val index = settings.identityProfiles.indexOfFirst { saved -> saved.name == originalName }
            if (index < 0) {
                throw UndineException.StateViolation("고칠 신원 프로필이 없습니다: '$originalName'")
            }
            settings.copy(
                identityProfiles = settings.identityProfiles.mapIndexed { position, saved ->
                    if (position == index) profile else saved
                },
            )
        }
    }

    // 없는 이름을 지우면 목록이 그대로라 `update` 가 쓰지 않고 끝낸다.
    override suspend fun deleteProfile(name: String) = settingsGateway.update { settings ->
        settings.copy(
            identityProfiles = settings.identityProfiles.filterNot { saved -> saved.name == name },
        )
    }

    /**
     * 후보는 **`Settings.recentRepositories` 뿐**이다 — 디스크를 훑지 않고, 같은 저장소가 여러 번
     * 들어갈 수 있는 열린 탭 목록도 쓰지 않는다 (결정 G34). 후보마다 그 저장소의 **로컬** config 를
     * 열어 `undine.identityProfile` 을 읽고, 같은 저장소를 가리키는 별칭 경로가 두 번 세지 않도록
     * **git 디렉터리 기준으로 중복을 제거**한다.
     *
     * [GitAccess] 를 쓰지 않는다 — 열려 있는 저장소가 아니라 다른 저장소들을 보는 조회이고,
     * `open` 은 활성 저장소를 바꾼다(UND-02 소유). 대신 읽기 전용 파일 접근을 직접 하고, 블로킹
     * IO 라서 [Dispatchers.IO] 경계를 여기서 연다.
     *
     * 후보 하나를 못 읽었다고 집계 전체를 실패로 만들지 않는다 — 삭제 확인이 실패 경로가 되면
     * 사용자는 확인 없이 지우게 된다. 대신 **확인하지 못한 저장소 수를 결과에 실어** 집계가 전수인지
     * 아닌지 화면이 말할 수 있게 한다 (결정 G36). 사라진 경로·저장소가 아닌 후보는 확인할 저장소
     * 자체가 없으므로 그 수에도 들어가지 않는다.
     */
    override suspend fun profileUsage(name: String): IdentityProfileUsage {
        val candidates = settingsGateway.load().recentRepositories
        return withContext(Dispatchers.IO) {
            val inspections = candidates.map(::inspectCandidate)
            val usingGitDirectories = inspections
                .filterIsInstance<CandidateInspection.Checked>()
                .filter { checked -> checked.assignedProfileName == name }
                .map { checked -> checked.repositoryIdentity }
                .toSet()
            IdentityProfileUsage(
                repositoryCount = usingGitDirectories.size,
                uncheckedRepositoryCount = inspections
                    .filterIsInstance<CandidateInspection.Unchecked>()
                    .map { unchecked -> unchecked.repositoryIdentity }
                    .toSet()
                    .size,
                globalIdentity = globalIdentityIn(globalConfig),
            )
        }
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
        gitAccess.configOperation(OPERATION_ASSIGNED) { config ->
            config.getString(SECTION_UNDINE, null, KEY_IDENTITY_PROFILE)?.takeIf { it.isNotBlank() }
        }

    /**
     * 원격 URL 은 여기서 직접 읽는다 — `RemoteGateway` 는 URL 을 노출하지 않고, 그 이유(토큰이
     * 섞일 수 있다)는 유지한다. 뽑아낸 호스트만 밖으로 나가고 URL 자체는 이 함수를 벗어나지 않는다.
     */
    override suspend fun remoteHost(): String? =
        gitAccess.configOperation(OPERATION_REMOTE_HOST) { config ->
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
        gitAccess.configOperation(operation) { config ->
            val beforeEdit = config.toText()
            try {
                edit(config)
                config.save()
            } catch (failure: IOException) {
                config.restoreTo(beforeEdit, failure)
                throw failure
            }
        }

}

/**
 * 열린 저장소의 config 로 [block] 을 수행한다. 잠금과 `Dispatchers.IO` 전환은 [GitAccess] 소유라
 * 여기서 다시 걸지 않는다.
 */
private suspend fun <T> GitAccess.configOperation(operation: String, block: (StoredConfig) -> T): T =
    try {
        withRepository { repository: Repository -> block(repository.config) }
    } catch (failure: IOException) {
        // 설정 파일을 쓰지 못했는데 성공으로 알리면 사용자는 신원이 바뀐 줄 안다.
        throw UndineException.GitOperationFailed(operation, failure)
    }

/** git 자신이 보는 사용자 전역 설정 파일. 이 경로로는 **읽기만** 한다. */
private fun userGlobalConfig(): StoredConfig = SystemReader.getInstance().openUserConfig(null, FS.DETECTED)

/**
 * 프로필 삭제 후 저장소들이 따르게 될 전역 신원.
 *
 * **읽지 못한 것을 "설정 없음" 으로 접지 않는다** (결정 G36) — 삭제 확인 화면이 "앞으로 쓸 identity
 * 가 없습니다" 라고 말하는데 사실은 있는 경우가 생긴다. 못 읽는 것을 실패(예외)로 바꾸지도 않는다.
 * 그러면 전역 설정이 깨진 기계에서 삭제 확인 자체가 막힌다. 셋을 그대로 셋으로 돌려준다.
 *
 * 이름과 이메일이 둘 다 있어야 git 이 커밋할 수 있으므로 반쪽 설정은
 * [GlobalIdentity.NotConfigured] 다 — 읽기는 성공했고 쓸 수 있는 신원이 없다는 뜻이다.
 */
private fun globalIdentityIn(globalConfig: () -> StoredConfig): GlobalIdentity {
    val config = loadedOrNull { globalConfig().also { it.load() } } ?: return GlobalIdentity.Unreadable
    val name = config.getString(ConfigConstants.CONFIG_USER_SECTION, null, KEY_NAME)
    val email = config.getString(ConfigConstants.CONFIG_USER_SECTION, null, KEY_EMAIL)
    return if (name.isNullOrBlank() || email.isNullOrBlank()) {
        GlobalIdentity.NotConfigured
    } else {
        GlobalIdentity.Configured(Person(name, email))
    }
}

/**
 * 후보 저장소 하나를 확인한 결과. **"확인해 보니 안 쓴다" 와 "확인하지 못했다" 를 가른다** —
 * 둘을 같은 값으로 접으면 집계가 전수인 척한다 (결정 G36).
 */
private sealed interface CandidateInspection {

    /** 경로가 사라졌거나 저장소가 아니다. 확인할 저장소 자체가 없으므로 미확인에도 넣지 않는다. */
    data object NotARepository : CandidateInspection

    /** 저장소는 있는데 로컬 config 를 읽지 못했다 — 쓰는지 안 쓰는지 알 수 없다. */
    data class Unchecked(val repositoryIdentity: String) : CandidateInspection

    /** 로컬 config 를 읽었다. [assignedProfileName] 이 없으면 지정된 적이 없다는 뜻이다. */
    data class Checked(
        val repositoryIdentity: String,
        val assignedProfileName: String?,
    ) : CandidateInspection
}

/** [path] 후보가 저장소면 그 git 디렉터리를 확인한다. */
private fun inspectCandidate(path: RepositoryPath): CandidateInspection {
    val gitDirectory = gitDirectoryOf(File(path.value)) ?: return CandidateInspection.NotARepository
    return inspectGitDirectory(gitDirectory)
}

/**
 * [target] 의 git 디렉터리. 저장소가 아니거나 경로가 사라졌으면 `null` 이다.
 *
 * 부모로 거슬러 올라가지 않게 천장을 둔다 — 후보 경로가 지워졌을 때 그 **상위** 저장소를 대신
 * 세면 집계가 조용히 틀린다. `RepositoryHolder` 가 저장소를 여는 규칙과 같은 이유다.
 */
private fun gitDirectoryOf(target: File): File? {
    if (!target.isDirectory) return null
    val builder = FileRepositoryBuilder()
    target.absoluteFile.parentFile?.let(builder::addCeilingDirectory)
    builder.findGitDir(target)
    return builder.gitDir
}

/**
 * [gitDirectory] 저장소의 **로컬** config 에 적힌 프로필 이름을 읽는다. 전역 설정을 base 로 물지
 * 않는다 — 전역에 적힌 값이 저장소가 지정한 것처럼 보이면 집계가 부풀려진다.
 */
private fun inspectGitDirectory(gitDirectory: File): CandidateInspection {
    val repositoryIdentity = repositoryIdentityOf(gitDirectory)
    val config = loadedOrNull {
        FileBasedConfig(File(gitDirectory, CONFIG_FILE), FS.DETECTED).also { it.load() }
    } ?: return CandidateInspection.Unchecked(repositoryIdentity)
    return CandidateInspection.Checked(
        repositoryIdentity = repositoryIdentity,
        assignedProfileName = config.getString(SECTION_UNDINE, null, KEY_IDENTITY_PROFILE)
            ?.takeIf { assigned -> assigned.isNotBlank() },
    )
}

/**
 * 별칭 경로(`./x` · 심볼릭 링크)가 같은 저장소를 두 번 세지 않게 하는 키. 정규 경로를 얻지 못하면
 * 절대 경로로 대신한다 — 확인·미확인 어느 쪽이든 집계 단위는 저장소여야 한다.
 */
private fun repositoryIdentityOf(gitDirectory: File): String =
    (loadedOrNull { gitDirectory.canonicalFile } ?: gitDirectory.absoluteFile).path

/**
 * 읽기 전용 조회에서 **파일을 열지 못한 것**과 **설정이 깨진 것**을 `null` 로 돌려준다.
 *
 * 사용 집계는 삭제 확인을 위한 보조 정보라 실패로 만들지 않는다 (결정 A-J 2). 다만 이 `null` 은
 * **"없음" 이 아니라 "확인 못 함"** 이다 — 호출부가 둘을 가른 값으로 옮긴다 (결정 G36). 여기서
 * 삼키는 것은 이 두 종류뿐이고 다른 예외는 그대로 올라간다.
 */
private fun <T> loadedOrNull(open: () -> T): T? =
    try {
        open()
    } catch (ignoredUnreadable: IOException) {
        null
    } catch (ignoredInvalidConfig: ConfigInvalidException) {
        null
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
