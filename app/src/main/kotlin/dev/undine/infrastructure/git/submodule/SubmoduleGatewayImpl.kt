package dev.undine.infrastructure.git.submodule

import dev.undine.domain.UndineException
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.infrastructure.git.remote.GitCredentialHelperProvider
import dev.undine.infrastructure.git.remote.RemoteErrors
import dev.undine.infrastructure.git.remote.RemoteIdentity
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.IOException

private const val OPERATION_LIST = "submodule.list"
private const val OPERATION_INITIALIZE = "submodule.initialize"
private const val OPERATION_UPDATE = "submodule.update"
private const val OPERATION_ADD = "submodule.add"

/**
 * [SubmoduleGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 스레드 전환을 그 경계가 책임진다. 이 클래스는 락도 디스패처도 다시 걸지 않고,
 * 직접 여는 자원(`Git`·`SubmoduleWalk`·하위 `Repository`)만 `use {}` 로 닫는다.
 *
 * 추가·초기화·업데이트는 **clone 을 동반**하므로 JGit 예외 메시지에 원격 주소가 들어 있다.
 * 사설 저장소 주소 자체가 민감하므로 번역할 때 [RemoteErrors] 로 마스킹한다
 * (`.agent/rules/credential-handling.md`). 자격증명은 앱이 보관하지 않고
 * [GitCredentialHelperProvider] 가 git credential helper 에 위임한다 — `RemoteGatewayImpl` 과 같다.
 *
 * DI 배선(GitAccess → 이 구현 → 화면)은 이 티켓이 하지 않는다.
 */
class SubmoduleGatewayImpl(
    private val gitAccess: GitAccess,
    private val credentialsProvider: CredentialsProvider = GitCredentialHelperProvider(),
) : SubmoduleGateway {

    override suspend fun list(): List<Submodule> =
        guarded(OPERATION_LIST, remote = null, Repository::readSubmodules)

    override suspend fun initialize(path: String, recursive: Boolean) {
        requireSubmodulePath(path)
        onSubmodule(OPERATION_INITIALIZE, path) { repository, _ ->
            repository.initializeSubmodule(path, recursive, credentialsProvider)
        }
    }

    override suspend fun update(path: String, recursive: Boolean) {
        requireSubmodulePath(path)
        onSubmodule(OPERATION_UPDATE, path) { repository, target ->
            validateUpdatable(target)
            repository.updateSubmodule(path, recursive, credentialsProvider)
        }
    }

    override suspend fun add(url: String, path: String, branch: String?): Submodule {
        requireSubmodulePath(path)
        require(url.isNotBlank()) { "서브모듈 원격 주소가 비어 있습니다." }
        return guarded(OPERATION_ADD, RemoteIdentity(label = path, url = url)) { repository ->
            val rollback = SubmoduleAddRollback.capture(repository, path)
            runCatching {
                repository.addSubmodule(url, path, branch, credentialsProvider)
                repository.requireSubmodule(path)
            }.onFailure(rollback::restoreAfter).getOrThrow()
        }
    }

    /**
     * 대상 조회·판정·실행을 **하나의 임계구역** 안에서 끝낸다.
     *
     * 조회와 실행을 따로 [gitAccess] 에 들어가면 그 사이에 저장소 전환이 끼어, A 에서 읽은 상태로
     * B 의 같은 경로를 조작할 수 있다 — 한 논리 전이는 나누지 않는다.
     *
     * 원격 주소 마스킹에 쓸 identity 는 그 임계구역 안에서 읽어 밖으로 넘긴다. 대상을 찾기도 전에
     * 실패했다면 마스킹할 원격이 없으므로 null 인 채로 번역된다.
     */
    private suspend fun onSubmodule(
        operation: String,
        path: String,
        block: (Repository, Submodule) -> Unit,
    ) {
        var remote: RemoteIdentity? = null
        runCatching {
            gitAccess.withRepository { repository ->
                val target = repository.requireSubmodule(path)
                remote = target.remoteIdentity()
                block(repository, target)
            }
        }.getOrElse { failure -> throw failure.asDomainFailure(operation, remote) }
    }

    /**
     * [remote] 가 있으면 원격 주소를 지우며 번역하고, 없으면(전송이 없는 연산) 그대로 Git 연산
     * 실패로 옮긴다. 취소는 번역 대상이 아니라 그대로 전파된다.
     */
    private suspend fun <T> guarded(
        operation: String,
        remote: RemoteIdentity?,
        block: (Repository) -> T,
    ): T = runCatching { gitAccess.withRepository(block) }
        .getOrElse { failure -> throw failure.asDomainFailure(operation, remote) }
}

private fun Submodule.remoteIdentity(): RemoteIdentity = RemoteIdentity(label = path, url = url)

private fun requireSubmodulePath(path: String) {
    require(path.isNotBlank()) { "서브모듈 경로가 비어 있습니다." }
}

/** 초기화는 [SubmoduleGateway.initialize] 의 몫이다 — 여기서 조용히 clone 하지 않고 사유를 알린다. */
private fun validateUpdatable(target: Submodule) {
    if (!target.state.initialized) {
        throw UndineException.StateViolation("초기화되지 않은 서브모듈은 업데이트할 수 없습니다: '${target.path}'")
    }
}

private fun Throwable.asDomainFailure(operation: String, remote: RemoteIdentity?): Throwable = when (this) {
    is UndineException -> this
    is GitAPIException -> translate(operation, remote, this)
    is JGitInternalException -> translate(operation, remote, this)
    is ConfigInvalidException -> translate(operation, remote, this)
    is IOException -> translate(operation, remote, this)
    else -> this
}

private fun translate(operation: String, remote: RemoteIdentity?, failure: Exception): UndineException =
    remote?.let { identity -> RemoteErrors.translate(operation, identity, failure) }
        ?: UndineException.GitOperationFailed(operation, failure)
