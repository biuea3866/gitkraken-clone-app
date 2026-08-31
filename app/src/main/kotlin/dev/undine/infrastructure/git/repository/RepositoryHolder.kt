package dev.undine.infrastructure.git.repository

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.UndineException.InvalidRepositoryPath.Reason
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 세션당 하나의 장수명 JGit [Repository] 핸들을 보유한다.
 *
 * **이 클래스가 핸들의 유일한 소유자다.** 다른 Gateway 구현은 [sessionAt] 으로 얻은 핸들을 받아 쓰기만 하고
 * 닫지 않는다 — 조회마다 새로 열면 JGit 객체 캐시가 매번 무효화돼 대형 저장소의 이력 로딩이 느려진다.
 * 이전 활성 핸들은 저장소를 전환할 때, 명시적으로 [release]할 때, [close]할 때 닫힌다.
 *
 * 세션 키는 **정규화하고 심볼릭 링크를 해소한 절대 경로**다 (`toRealPath()`). 같은 저장소를 다른 경로
 * 표기로 두 번 여는 것을 막는다. 키로 조회하는 형태를 유지하면 다중 저장소 탭(UND-44)이 이 계약을
 * 깨지 않고 확장할 수 있다.
 *
 * 스레드 안전하다 — 상태 변경 메서드가 인스턴스 잠금으로 직렬화된다.
 */
class RepositoryHolder internal constructor(
    private val openRepository: (Path) -> Repository,
) {

    /** 프로덕션 생성자. 실제 JGit `FileRepository` 를 연다. */
    constructor() : this(::openFileRepository)

    private val sessions = LinkedHashMap<Path, Repository>()
    private var activeSessionKey: Path? = null

    /**
     * [path] 의 저장소 핸들을 돌려준다. 이미 같은 세션 키로 열려 있으면 **그 핸들을 그대로** 준다.
     *
     * 새 핸들을 먼저 연 뒤 이전 핸들을 닫는다 — 열기가 실패했을 때 이미 열려 있던 저장소를
     * 잃지 않게 하기 위해서다.
     *
     * @throws UndineException.InvalidRepositoryPath 경로가 없음·읽기 불가·베어·저장소 아님
     */
    @Synchronized
    fun open(path: RepositoryPath): Repository {
        val requestedKey = sessionKeyOf(path.value)
        if (requestedKey == activeSessionKey) return requireNotNull(sessions[requestedKey])

        val next = sessions[requestedKey] ?: openWorkTreeRepository(path.value, requestedKey).also {
            sessions[requestedKey] = it
        }
        activeSessionKey?.takeIf { it != requestedKey }?.let(::releaseByKey)
        activeSessionKey = requestedKey
        return next
    }

    /**
     * 다중 세션 캐시에 핸들을 연다. 활성 전환과 LRU 회수는 이 Holder의 같은 임계구역에서 끝난다.
     *
     * 기존 단일 저장소 경로는 [open]을 쓰므로, 비활성 핸들을 즉시 놓는 동작은 그대로 유지된다.
     *
     * **정규화된 세션 키를 돌려준다.** 호출부는 이 값으로만 회수·되돌리기를 지시한다 — 원본 경로를
     * 자기 식별자로 들고 있으면 `./` 나 심볼릭 링크 별칭에서 다른 탭의 활성 핸들을 닫게 된다.
     */
    @Synchronized
    fun openSession(path: RepositoryPath): Path {
        val requestedKey = sessionKeyOf(path.value)
        if (!sessions.containsKey(requestedKey)) {
            sessions[requestedKey] = openWorkTreeRepository(path.value, requestedKey)
        }
        activeSessionKey = requestedKey
        return requestedKey
    }

    /** [key] 로 열려 있는 핸들. 세션 키를 이미 아는 호출부(테스트·되돌리기)만 쓴다. */
    @Synchronized
    fun sessionAt(key: Path): Repository? = sessions[key]

    /**
     * 현재 활성 세션의 **키**. 열기 전이거나 [close] 후에는 null 이다.
     *
     * 핸들이 아니라 키를 주는 이유는 호출부가 **락을 기다리기 전에** 실행 대상을 정하기 때문이다
     * (UND-80). 핸들을 미리 쥐여 주면 대기 중 세션이 닫혀도 그 사실을 모른 채 닫힌 핸들로
     * 실행한다 — 키로 받아 두면 [sessionAt] 조회가 그 창을 드러낸다.
     */
    @Synchronized
    fun activeSessionKey(): Path? = activeSessionKey

    /**
     * 특정 비활성/LRU 세션의 핸들을 닫는다. 현재 세션을 회수하면 활성 세션도 비운다.
     *
     * 인자는 [openSession] 이 돌려준 **정규화된 키**다. 원본 경로를 다시 정규화하지 않는다 —
     * 정규화 지점이 둘이면 별칭 경로에서 서로 다른 세션을 가리키게 된다.
     */
    @Synchronized
    fun releaseSession(key: Path) {
        releaseByKey(key)
    }

    /**
     * 열린 세션 집합을 [sessionKeys] 와 일치시키고 [active] 를 활성 세션으로 만든다 — 되돌리기 전용이다.
     *
     * 회수(목록 밖)와 다시 열기(목록 안인데 닫힌 것)를 **한 임계구역**에서 끝낸다. 되살릴 수 없는
     * 세션은 예외로 올리지 않고 결과에서 빼 호출부의 장부가 실제 집합과 같아지게 한다 — 되돌리기가
     * 다시 실패를 만들면 원래 실패를 가린다.
     *
     * @return 이 호출 뒤 실제로 열려 있는 세션 키 (인자 순서를 유지한다)
     */
    @Synchronized
    fun restoreSessions(sessionKeys: List<Path>, active: Path?): List<Path> {
        val retained = sessionKeys.distinct().filter(::reopened)

        sessions.keys.toList()
            .filterNot(retained::contains)
            .forEach(::releaseByKey)
        activeSessionKey = active?.takeIf(retained::contains)
        return retained
    }

    /** 현재 핸들을 닫고 세션을 비운다. 열려 있지 않으면 아무 일도 하지 않는다. */
    @Synchronized
    fun close() {
        sessions.values.toList().forEach(Repository::close)
        sessions.clear()
        activeSessionKey = null
    }

    /**
     * 활성 세션을 회수하면 활성 키를 **비운다** — 남아 있는 아무 세션으로 슬쩍 옮기지 않는다.
     * 다음에 어느 탭을 활성화할지는 호출부의 정책이고, 여기서 정하면 조용한 결정이 된다.
     */
    private fun releaseByKey(key: Path) {
        sessions.remove(key)?.close()
        if (activeSessionKey == key) activeSessionKey = null
    }

    /**
     * 되돌리기용 열기 — 이미 열려 있으면 그대로 두고, 닫혀 있으면 다시 연다.
     * 열 수 없는 세션은 `false` 다. 조용한 실패가 아니라 [restoreSessions] 의 결과에서 빠져
     * 호출부 장부에 그대로 반영된다.
     *
     * 인자가 이미 정규화된 키이므로 여기서 다시 정규화하지 않는다.
     */
    private fun reopened(key: Path): Boolean =
        sessions.containsKey(key) ||
            runCatching { openWorkTreeRepository(key.toString(), key) }
                .getOrNull()
                ?.also { repository -> sessions[key] = repository } != null

    private fun openWorkTreeRepository(raw: String, sessionKey: Path): Repository {
        val opened = try {
            openRepository(sessionKey)
        } catch (cause: IOException) {
            throw invalidPath(raw, Reason.NOT_A_REPOSITORY, cause)
        }
        if (!opened.isBare) return opened

        opened.close()
        throw UndineException.InvalidRepositoryPath(raw, Reason.BARE_REPOSITORY)
    }
}

/**
 * 판정 순서는 **존재 → 읽기 권한 → (호출부의) 베어 → 그 밖의 열기 실패** 이고 먼저 걸리는 것이 이긴다.
 * 사용자가 취할 행동이 사유마다 다르므로 뭉뚱그리지 않는다.
 */
private fun sessionKeyOf(raw: String): Path {
    val candidate = Path.of(raw)
    val reason = when {
        !Files.exists(candidate) -> Reason.NOT_FOUND
        !Files.isReadable(candidate) -> Reason.PERMISSION_DENIED
        else -> null
    }
    if (reason != null) throw UndineException.InvalidRepositoryPath(raw, reason)

    return try {
        candidate.toRealPath()
    } catch (cause: IOException) {
        throw invalidPath(raw, Reason.NOT_FOUND, cause)
    }
}

/**
 * 경로 자체가 저장소 루트(또는 베어 저장소 디렉터리)여야 한다. 부모 디렉터리로 거슬러 올라가
 * 상위 저장소를 찾지 않는다 — 사용자가 고른 경로와 앱이 연 저장소가 달라지는 쪽이 더 헷갈린다.
 */
private fun openFileRepository(workTree: Path): Repository {
    val target = workTree.toFile()
    val builder = FileRepositoryBuilder().setMustExist(true)
    target.parentFile?.let(builder::addCeilingDirectory)
    builder.findGitDir(target)
    if (builder.gitDir == null) throw RepositoryNotFoundException(target)
    return builder.build()
}

/**
 * [UndineException.InvalidRepositoryPath] 에는 cause 슬롯이 없다 (domain 계약).
 * 원인을 잃지 않으려고 suppressed 로 붙여 둔다 — 조용히 삼키지 않는다.
 */
private fun invalidPath(raw: String, reason: Reason, cause: Throwable): UndineException =
    UndineException.InvalidRepositoryPath(raw, reason).also { it.addSuppressed(cause) }
