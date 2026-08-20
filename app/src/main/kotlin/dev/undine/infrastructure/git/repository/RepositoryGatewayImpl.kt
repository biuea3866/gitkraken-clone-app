package dev.undine.infrastructure.git.repository

import dev.undine.domain.OpenedRepository
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import org.eclipse.jgit.api.errors.GitAPIException
import java.io.IOException

/**
 * [RepositoryGateway] 의 JGit 구현. 앱이 저장소를 **여는 유일한 경로**다.
 *
 * 공유 핸들의 직렬화와 `Dispatchers.IO` 실행은 [GitAccess] 가 책임진다 — 다른 Gateway 구현도
 * 같은 인스턴스를 받아 같은 경계를 공유하므로, 이 클래스는 락이나 `withContext` 를 다시 걸지 않는다.
 *
 * 배선(GitAccess → 각 Gateway 구현)은 UND-26 이 한다.
 */
class RepositoryGatewayImpl(
    private val gitAccess: GitAccess = GitAccess(),
) : RepositoryGateway {

    override suspend fun open(path: RepositoryPath): OpenedRepository =
        gitAccess.open(path) { repository ->
            translateFailure("repository.open") { repository.toOpenedRepository() }
        }

    override suspend fun status(): WorkingTreeStatus =
        gitAccess.withRepository { repository ->
            translateFailure("repository.status") { repository.toWorkingTreeStatus() }
        }

    override suspend fun close(): Unit = gitAccess.close()
}

/**
 * JGit 예외를 도메인 예외로 번역한다. 사용자가 고칠 수 없는 실패만 여기로 오므로
 * [UndineException.GitOperationFailed] 가 맞고, cause 를 보존해 로그로 추적할 수 있게 한다.
 * `CancellationException` 은 이 타입에 걸리지 않아 취소가 그대로 전파된다.
 */
private inline fun <T> translateFailure(operation: String, block: () -> T): T =
    try {
        block()
    } catch (cause: IOException) {
        throw UndineException.GitOperationFailed(operation, cause)
    } catch (cause: GitAPIException) {
        throw UndineException.GitOperationFailed(operation, cause)
    }
