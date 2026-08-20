package dev.undine.infrastructure.git.repository

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Repository

internal const val REPOSITORY_NOT_OPEN = "저장소가 열려 있지 않습니다"

/**
 * 공유 JGit [Repository] 핸들에 접근하는 **유일한 통로**다.
 *
 * JGit `Repository` 는 스레드 안전하지 않으므로 모든 접근을 [Mutex] 로 직렬화하고,
 * Git I/O 를 `Dispatchers.IO` 에서 돌린다 — 호출부가 디스패처를 잊어도 UI 가 멈추지 않아야 한다.
 * 두 책임을 여기에 모은 이유는 **다른 Gateway 구현이 같은 경계를 공유해야** 하기 때문이다.
 * 각 Gateway 는 `Repository` 가 아니라 이 클래스를 생성자로 받고, 자기 안에서 락이나
 * `withContext` 를 다시 걸지 않는다.
 *
 * 핸들의 수명은 [RepositoryHolder] 가 소유한다. 배선(GitAccess → 각 Gateway 구현)은 UND-26 이 한다.
 */
class GitAccess(
    private val holder: RepositoryHolder = RepositoryHolder(),
) {

    private val serialAccess = Mutex()

    /**
     * [path] 의 저장소를 열고(이미 열려 있으면 전환하고) 그 핸들로 [block] 을 수행한다.
     * 저장소를 여는 경로는 UND-02 소유이므로 다른 Gateway 는 이 메서드를 쓰지 않는다.
     */
    suspend fun <T> open(path: RepositoryPath, block: (Repository) -> T): T =
        onGitThread { block(holder.open(path)) }

    /**
     * 현재 열려 있는 핸들로 [block] 을 수행한다.
     *
     * 열기 전이나 [close] 후의 호출은 빈 결과가 아니라 실패다 — 빈 결과를 주면 화면이
     * "변경 없음" 으로 오해한다.
     *
     * @throws UndineException.StateViolation 저장소가 열려 있지 않을 때
     */
    suspend fun <T> withRepository(block: (Repository) -> T): T = onGitThread {
        val repository = holder.current() ?: throw UndineException.StateViolation(REPOSITORY_NOT_OPEN)
        block(repository)
    }

    /** 현재 핸들을 닫는다. 열려 있지 않으면 아무 일도 하지 않는다. */
    suspend fun close(): Unit = onGitThread { holder.close() }

    private suspend fun <T> onGitThread(block: () -> T): T =
        withContext(Dispatchers.IO) { serialAccess.withLock { block() } }
}
