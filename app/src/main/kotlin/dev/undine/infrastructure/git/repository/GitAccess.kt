package dev.undine.infrastructure.git.repository

import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositorySessionKey
import dev.undine.domain.RepositorySessions
import dev.undine.domain.UndineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Repository
import java.nio.file.Path

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
     * 탭 세션 전이 **하나 전체**를 이 클래스의 임계구역 안에서 수행한다.
     *
     * 락을 전이의 시작과 끝에 한 번씩 잡는 것이 요점이다 — 세션 조작마다 잡았다 놓으면 호출 사이에
     * 다른 전이가 끼어들어, 먼저 시작한 전이의 되돌리기가 뒤 전이의 상태를 덮는다 (결정 C2 정정 5).
     * [block] 이 받는 [RepositorySessions] 는 이미 락을 쥔 채 홀더를 직접 부르므로 다시 잠그지 않는다.
     *
     * 설정 저장처럼 Git 이 아닌 대기도 이 구역 안에서 일어난다. 그동안 다른 Git 접근이 기다리는 것은
     * 의도된 비용이다 — 장부와 핸들이 갈라지는 쪽이 훨씬 비싸다.
     */
    suspend fun <T> withSessions(block: suspend (RepositorySessions) -> T): T =
        withContext(Dispatchers.IO) { serialAccess.withLock { block(HeldSessions(holder)) } }

    /**
     * 현재 열려 있는 핸들로 [block] 을 수행한다.
     *
     * 열기 전이나 [close] 후의 호출은 빈 결과가 아니라 실패다 — 빈 결과를 주면 화면이
     * "변경 없음" 으로 오해한다.
     *
     * @throws UndineException.StateViolation 저장소가 열려 있지 않을 때
     */
    suspend fun <T> withRepository(block: (Repository) -> T): T = onGitThread { block(heldRepository()) }

    /**
     * **여러 Git 조작으로 이루어진 시퀀스 하나 전체**를 이 클래스의 임계구역 안에서 수행한다.
     *
     * [withRepository] 를 조작마다 부르면 호출 사이에 다른 접근이 끼어든다 — 체크아웃과 그 위의
     * 병합 사이에 다른 체크아웃이 들어오면 의도하지 않은 브랜치에서 병합이 실행된다. 시퀀스를
     * 여는 지점을 여기에 두는 이유는 [withSessions] 와 같다: **직렬화는 자원을 소유한 쪽이 한다**
     * (결정 A-N1·A-L3·G4). 각 Gateway 구현이 자기 잠금을 덧대면 이미 쥔 락을 다시 잡는다.
     *
     * [block] 은 이미 락을 쥔 핸들을 받으므로 그 안에서 [withRepository] 를 다시 부르지 않는다 —
     * 각 Gateway 는 **이 구역용 내부 경로**를 따로 노출한다.
     *
     * 취소는 **시퀀스가 시작되기 전에만** 관측된다. [block] 은 정지 함수가 아니므로 한 번 시작하면
     * 중간에 끊기지 않고, 취소가 그 뒤에 떨어지면 호출자에게는 결과 대신 `CancellationException`
     * 이 도착한다. 그래서 **변경과 그 결과의 소비(Undo 기록 등)를 한 단위로 묶는 것은 호출자의
     * 몫**이다 — 그 계약은 각 Gateway 의 변경 연산이 명시한다 (결정 A-L2·G4).
     *
     * @throws UndineException.StateViolation 저장소가 열려 있지 않을 때
     */
    suspend fun <T> withSequence(block: (Repository) -> T): T = onGitThread { block(heldRepository()) }

    /** 열려 있는 모든 핸들을 닫는다. 열려 있지 않으면 아무 일도 하지 않는다. */
    suspend fun close(): Unit = onGitThread { holder.close() }

    private fun heldRepository(): Repository =
        holder.current() ?: throw UndineException.StateViolation(REPOSITORY_NOT_OPEN)

    private suspend fun <T> onGitThread(block: () -> T): T =
        withContext(Dispatchers.IO) { serialAccess.withLock { block() } }
}

/**
 * [GitAccess.withSessions] 가 락을 쥔 동안에만 살아 있는 세션 조작.
 *
 * 홀더를 **직접** 부른다 — [GitAccess] 의 공개 메서드를 거치면 이미 쥔 락을 다시 잡으려다 멈춘다.
 * 홀더 자신의 인스턴스 잠금이 있으므로 여기서 추가로 감싸지 않는다 (결정 A-N1).
 */
private class HeldSessions(private val holder: RepositoryHolder) : RepositorySessions {

    override suspend fun open(path: RepositoryPath): RepositorySessionKey =
        holder.openSession(path).toSessionKey()

    override suspend fun release(key: RepositorySessionKey) {
        holder.releaseSession(key.toPath())
    }

    override suspend fun close() {
        holder.close()
    }

    override suspend fun restoreSessions(
        sessions: List<RepositorySessionKey>,
        active: RepositorySessionKey?,
    ): List<RepositorySessionKey> =
        holder.restoreSessions(sessions.map(RepositorySessionKey::toPath), active?.toPath())
            .map(Path::toSessionKey)
}

/**
 * 세션 키는 홀더가 정한 **정규화된 절대 경로**의 문자열 표현이다. 이 두 변환이 domain 계약과
 * 홀더 사이의 유일한 통로이며, 여기서 경로를 다시 해석(정규화·상대 경로 해소)하지 않는다.
 */
private fun Path.toSessionKey(): RepositorySessionKey = RepositorySessionKey(toString())

private fun RepositorySessionKey.toPath(): Path = Path.of(value)
