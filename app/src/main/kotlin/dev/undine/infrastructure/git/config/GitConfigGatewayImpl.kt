package dev.undine.infrastructure.git.config

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.gitconfig.EffectiveValue
import dev.undine.domain.gitconfig.GitConfigGateway
import dev.undine.domain.gitconfig.GitConfigKey
import dev.undine.domain.gitconfig.GitConfigSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.IOException

/** 화면이 어떤 동작이 실패했는지 말할 수 있게 실패 이름을 하나로 고정한다 (결정 G35 UND-75 2). */
private const val OPERATION = "read git config"

/**
 * [GitConfigGateway] 의 JGit 설정 파일 구현.
 *
 * **범위마다 base 없는 설정을 따로 연다.** `Repository.getConfig()` 는 전역·시스템을 base 로
 * 물고 있어 값이 어디서 왔는지 구분하지 못한다 — 출처를 돌려주는 것이 이 계약의 요점이므로
 * 세 파일을 각각 읽고 Git 우선순위대로 훑는다.
 *
 * [dev.undine.infrastructure.git.repository.GitAccess] 를 쓰지 않는다. `withRepository()` 는
 * 저장소가 열려 있지 않으면 `StateViolation` 을 던지는데, 이 경로는 **저장소 없이도** 전역·시스템
 * 값을 말할 수 있어야 한다. 공유 `Repository` 핸들을 만지지 않으므로 직렬화도 필요 없고,
 * 파일 읽기는 여기서 `Dispatchers.IO` 로 넘긴다.
 *
 * 전역·시스템 설정 파일을 생성자로 받는 이유는 UND-78 의 `appDirectory` 와 같다 — 위치를
 * 알아내는 지식은 한 곳(기본값)에 두고, 테스트는 진짜 파일을 진짜 JGit 파서로 읽는다.
 */
class GitConfigGatewayImpl(
    private val globalConfigFile: File? = defaultGlobalConfigFile(),
    private val systemConfigFile: File? = defaultSystemConfigFile(),
) : GitConfigGateway {

    override suspend fun effectiveValues(
        repository: RepositoryPath?,
    ): Map<GitConfigKey, EffectiveValue> = withContext(Dispatchers.IO) {
        val scopes = listOf(
            GitConfigSource.REPOSITORY to repository?.let(::repositoryConfigFile),
            GitConfigSource.GLOBAL to globalConfigFile,
            GitConfigSource.SYSTEM to systemConfigFile,
        ).map { (source, file) -> source to loadConfig(file) }

        GitConfigKey.entries.mapNotNull { key -> scopes.firstEffectiveValue(key) }.toMap()
    }

    /**
     * 저장소 범위의 설정 파일. 저장소가 아닌 경로는 `null` 이다 — 저장소 범위 없이 전역·시스템만 본다.
     *
     * 연결된 워크트리는 자기 `gitdir` 가 아니라 **공용 디렉터리**의 `config` 를 쓴다. 그 해소는
     * JGit 의 [FileRepositoryBuilder.setup] 에 맡긴다 — 직접 계산하면 Git 의 규칙을 한 벌 더 갖게 된다.
     */
    private fun repositoryConfigFile(repository: RepositoryPath): File? {
        val target = File(repository.value)
        val builder = FileRepositoryBuilder()
        target.parentFile?.let(builder::addCeilingDirectory)
        builder.findGitDir(target)
        if (builder.gitDir == null) return null

        return try {
            builder.setup()
            File(builder.gitCommonDir ?: builder.gitDir, Constants.CONFIG)
        } catch (failure: IOException) {
            throw UndineException.GitOperationFailed(OPERATION, failure)
        } catch (invalid: IllegalArgumentException) {
            // JGit 은 저장소 config 파싱 실패를 IllegalArgumentException 으로 바꿔 올린다.
            throw UndineException.GitOperationFailed(OPERATION, invalid)
        }
    }
}

/**
 * base 없는 설정 하나를 읽는다. **파일이 없는 것은 부재**(빈 설정)이고, 있는데 읽히지 않거나
 * 파싱되지 않는 것은 실패다 — 둘을 섞으면 손상된 파일이 "설정 안 함" 으로 보인다.
 */
private fun loadConfig(file: File?): Config? {
    if (file == null || !file.isFile) return null
    return try {
        FileBasedConfig(file, FS.DETECTED).also(FileBasedConfig::load)
    } catch (failure: IOException) {
        throw UndineException.GitOperationFailed(OPERATION, failure)
    } catch (invalid: ConfigInvalidException) {
        throw UndineException.GitOperationFailed(OPERATION, invalid)
    }
}

/**
 * Git 우선순위(저장소 > 전역 > 시스템)대로 훑어 **처음 값이 있는 범위**를 고른다.
 * 목록의 선언 순서가 곧 우선순위다 ([GitConfigSource]).
 *
 * **`null` 만 부재다** (결정 G36 UND-75). `[user] name =` 는 Git 에서 *설정된 빈 값*이고,
 * 저장소의 빈 값은 전역 값을 가린다 — 빈 문자열을 부재로 접으면 하위 범위로 잘못 폴백해
 * 출처와 우선순위가 어긋난다. 값은 앞뒤 공백까지 그대로 싣는다: 따옴표로 감싼 값의 공백은
 * 사용자가 의도한 것이고, 해석(`asBoolean()` 의 trim)은 domain 의 몫이다.
 */
private fun List<Pair<GitConfigSource, Config?>>.firstEffectiveValue(
    key: GitConfigKey,
): Pair<GitConfigKey, EffectiveValue>? = firstNotNullOfOrNull { (source, config) ->
    config?.getString(key.section, null, key.key)
        ?.let { raw -> key to EffectiveValue(raw, source) }
}

/** `~/.gitconfig` (XDG 배치를 쓰면 그쪽). 위치 판단은 JGit 이 이미 알고 있다. */
private fun defaultGlobalConfigFile(): File? =
    SystemReader.getInstance().openUserConfig(null, FS.DETECTED).file

/** `/etc/gitconfig`. `GIT_CONFIG_NOSYSTEM` 이 켜져 있으면 JGit 이 파일 없는 설정을 준다. */
private fun defaultSystemConfigFile(): File? =
    SystemReader.getInstance().openSystemConfig(null, FS.DETECTED).file
