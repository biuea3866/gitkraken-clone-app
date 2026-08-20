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
 * **이 클래스가 핸들의 유일한 소유자다.** 다른 Gateway 구현은 [current] 로 얻은 핸들을 받아 쓰기만 하고
 * 닫지 않는다 — 조회마다 새로 열면 JGit 객체 캐시가 매번 무효화돼 대형 저장소의 이력 로딩이 느려진다.
 * 이전 핸들은 **저장소를 전환할 때와 [close] 할 때만** 닫힌다.
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

    private var sessionKey: Path? = null
    private var repository: Repository? = null

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
        val opened = repository
        if (opened != null && requestedKey == sessionKey) return opened

        val next = openWorkTreeRepository(path.value, requestedKey)
        opened?.close()
        sessionKey = requestedKey
        repository = next
        return next
    }

    /** 현재 열려 있는 핸들. 열기 전이거나 [close] 후에는 null 이다. */
    @Synchronized
    fun current(): Repository? = repository

    /** 현재 핸들을 닫고 세션을 비운다. 열려 있지 않으면 아무 일도 하지 않는다. */
    @Synchronized
    fun close() {
        repository?.close()
        repository = null
        sessionKey = null
    }

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
