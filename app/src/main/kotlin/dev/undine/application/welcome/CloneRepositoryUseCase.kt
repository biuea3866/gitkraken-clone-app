package dev.undine.application.welcome

import dev.undine.domain.Progress
import dev.undine.domain.RemoteGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.SettingsGateway
import dev.undine.domain.UndineException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/** 스테이징 디렉터리 이름 앞머리. 점으로 시작해 파일 탐색기에서 숨겨지고, 앱 소유임이 드러난다. */
private const val STAGING_PREFIX = ".undine-clone-"

private val LOGGER: Logger = Logger.getLogger("dev.undine.application.welcome.CloneRepositoryUseCase")

/**
 * clone 요청의 결말 중 **정상 흐름**만 담는다. 인증 실패·전송 실패 같은 오류는 예외로 올린다
 * (`exception-handling` 3항 — 실패 종류는 `UndineException` 계층이 닫고 있다).
 */
sealed interface CloneOutcome {

    /** 클론이 끝나 [path] 에 저장소가 생겼고 최근 목록에도 올라갔다. */
    data class Cloned(val path: RepositoryPath) : CloneOutcome

    /**
     * 대상이 이미 있고 비어 있지 않아 **시작하지 않았다**. 오류가 아니라 사용자가 다시 고르면 되는
     * 정상 갈림길이라 예외가 아닌 결과 값이다.
     */
    data object TargetNotEmpty : CloneOutcome
}

/**
 * 원격 저장소를 클론하고 최근 목록에 올린다.
 *
 * **롤백**: clone 은 대상 옆에 만든 **앱 전용 스테이징 디렉터리**에 받고, 다 받은 뒤에만 대상으로 옮긴다.
 * 실패·취소로 끝나면 지우는 것은 그 스테이징 디렉터리뿐이다 — clone 중 사용자가 대상에 파일을 넣거나
 * 다른 프로세스가 경로를 바꿔치기해도, 앱이 만들지 않은 데이터를 앱이 지우는 일이 생기지 않는다.
 * (대상 디렉터리를 만들어 두고 실패 시 재귀 삭제하면 그 사이 들어온 사용자 파일까지 지운다.)
 * 지우지 못하면 [execute] 의 `onCleanupFailed` 로 경로를 알려 수동 정리를 안내한다.
 *
 * 클론 깊이(shallow) 옵션은 이 화면 범위에서 제공하지 않는다 — `RemoteGateway.clone` 계약에 depth 가
 * 없고 계약 확장은 이 티켓 소유가 아니다 (wave 3 결정 A4).
 *
 * @param deleteDirectory 정리 단계의 삭제. 기본값은 **링크를 따라가지 않는** 재귀 삭제이며,
 *   **정리 실패 경로를 검증하기 위한 테스트 이음매**로만 교체한다 — 삭제 실패는 권한·잠금에 좌우돼
 *   OS 별로 재현이 불안정하다.
 */
class CloneRepositoryUseCase(
    private val remoteGateway: RemoteGateway,
    private val settingsGateway: SettingsGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deleteDirectory: (File) -> Boolean = ::deleteTreeWithoutFollowingLinks,
) {
    /**
     * @param onProgress 진행률 콜백. `RemoteGateway` 가 올리는 값을 그대로 통과시킨다.
     * @param onCleanupFailed 실패·취소 후 정리에 실패한 경로. 취소된 코루틴은 값을 돌려받을 수 없어
     *   반환값이 아니라 콜백이다.
     */
    suspend fun execute(
        url: String,
        into: RepositoryPath,
        onProgress: (Progress) -> Unit,
        onCleanupFailed: (RepositoryPath) -> Unit,
    ): CloneOutcome {
        val target = File(into.value)
        val staging = withContext(ioDispatcher) { prepareStaging(target) } ?: return CloneOutcome.TargetNotEmpty
        // 만든 그 디렉터리인지 나중에 증명할 지문. 이름·모양은 흉내 낼 수 있어도 이것은 그 아이노드다.
        val stagingIdentity = withContext(ioDispatcher) { staging.identity() }

        var promoted = false
        try {
            remoteGateway.clone(url, RepositoryPath(staging.path), onProgress)
            // 대상으로 옮기기까지가 성공이다 — 옮기지 못하면 저장소가 생기지 않았다.
            promoted = withContext(NonCancellable + ioDispatcher) { promote(staging, target) }
        } finally {
            if (!promoted) cleanUp(staging, stagingIdentity, onCleanupFailed)
        }
        if (!promoted) throw UndineException.GitOperationFailed(operation = "clone")

        // 대상이 이미 생겼으므로 최근 목록 저장까지는 취소로 건너뛰지 않는다 — 저장소는 있는데
        // 목록에 없으면 사용자는 clone 이 실패한 것으로 읽는다.
        withContext(NonCancellable) { settingsGateway.recordMostRecent(into) }
        return CloneOutcome.Cloned(into)
    }

    /**
     * clone 을 받을 앱 전용 디렉터리를 대상 옆에 만든다. **대상 자체는 만들지 않는다** —
     * 성공한 뒤 [promote] 가 옮기는 시점에 생긴다.
     *
     * 대상이 이미 있고 비어 있지 않으면(디렉터리가 아닌 경우 포함) `null` 로 거부한다. 스테이징을 만들지
     * 못하는 경우도 clone 을 시작할 수 없다는 점에서 같은 거부다.
     *
     * 파일 시스템 접근이라 호출부가 IO 디스패처로 감싼다.
     */
    private fun prepareStaging(target: File): File? {
        val parent = target.absoluteFile.parentFile
        // 부모가 없는 경로(파일 시스템 루트)도, 이미 차 있는 대상도 clone 을 시작할 수 없다.
        if (parent == null || (target.exists() && !target.isEmptyDirectory())) return null
        parent.mkdirs()
        // 같은 대상에 두 clone 이 붙어도 서로의 스테이징을 건드리지 않도록 이름을 유일하게 만든다.
        return File(parent, "$STAGING_PREFIX${target.name}-${UUID.randomUUID()}").takeIf { it.mkdir() }
    }

    /**
     * 받아 둔 스테이징을 대상 경로로 옮긴다.
     *
     * **사용자가 미리 만든 디렉터리는 지우지 않는다.** 대상이 없으면 스테이징을 그 이름으로 옮기고,
     * 대상이 이미 있으면(사용자가 고른 빈 폴더) **그 안으로 내용을 옮긴다** — 디렉터리 자체를 지우고
     * 다시 만들면 사용자가 그 경로에 걸어 둔 권한·확장 속성·다른 프로세스의 열린 핸들이 사라지고,
     * 지우기와 다시 만들기 사이에 경로가 교체될 수 있다.
     *
     * 이동은 [Files.move] 로 한다 — 실패 사유를 예외로 구분할 수 있고, 같은 파일 시스템 안에서는
     * [StandardCopyOption.ATOMIC_MOVE] 로 반쯤 옮겨진 상태가 생기지 않는다. 대상이 있으면
     * 덮어쓰기 옵션을 주지 않으므로 그 이름을 가로채지 않는다.
     */
    private fun promote(staging: File, target: File): Boolean = runCatching {
        if (!target.exists()) {
            moveDirectory(staging.toPath(), target.toPath())
            return@runCatching true
        }
        moveContentsInto(staging, target)
    }.getOrDefault(false)

    /**
     * 스테이징 **안의 항목만** 대상 안으로 옮기고 빈 스테이징을 지운다. 대상 디렉터리 자체는 손대지 않는다.
     *
     * 대상이 비어 있지 않으면 옮기지 않는다 — 그 사이 파일이 들어왔다면 사용자 데이터이고,
     * 같은 이름이 있으면 덮어쓰게 된다.
     */
    private fun moveContentsInto(staging: File, target: File): Boolean {
        val targetPath = target.toPath()
        val usableTarget = !Files.isSymbolicLink(targetPath) &&
            Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS) &&
            target.isEmptyDirectory()
        val entries = staging.listFiles()
        if (!usableTarget || entries == null) return false
        entries.forEach { entry -> Files.move(entry.toPath(), targetPath.resolve(entry.name)) }
        // 내용을 다 옮겼으면 빈 스테이징만 남는다. 지우지 못해도 clone 자체는 성공이다.
        Files.deleteIfExists(staging.toPath())
        return true
    }

    /**
     * 실패·취소로 남은 스테이징을 지운다.
     *
     * **앱이 만든 그 디렉터리인지 지문으로 확인한다.** 이름 접두사와 "심볼릭 링크가 아닌 디렉터리" 는
     * 흉내 낼 수 있으므로, 만든 시점에 잡아 둔 [File.identity]([java.nio.file.attribute.BasicFileAttributes.fileKey],
     * 없으면 생성 시각+경로)와 대조한다. 어긋나면 그 사이 다른 주체가 경로를 차지한 것이므로 지우지 않고
     * 수동 정리로 넘긴다 — 링크를 따라 지우면 링크가 가리키는 사용자 데이터를 지우게 된다.
     * 삭제도 링크를 따라가지 않는다([deleteDirectory] 기본 구현).
     */
    private suspend fun cleanUp(
        staging: File,
        stagingIdentity: Any?,
        onCleanupFailed: (RepositoryPath) -> Unit,
    ) {
        // 취소도 이 경로로 온다 — NonCancellable 이 아니면 정리 자체가 취소돼 반쯤 채운 디렉터리가 남는다.
        withContext(NonCancellable + ioDispatcher) {
            if (!staging.exists()) return@withContext
            if (!isAppOwnedStaging(staging, stagingIdentity)) {
                onCleanupFailed(RepositoryPath(staging.path))
                return@withContext
            }
            if (!deleteDirectory(staging)) onCleanupFailed(RepositoryPath(staging.path))
        }
    }
}

/**
 * 디렉터리의 지문. 같은 경로에 새로 만든 디렉터리와 **구별되는** 값이어야 한다 —
 * `fileKey` 는 POSIX 에서 (device, inode) 라 경로가 교체되면 달라진다. 제공하지 않는 파일 시스템에서는
 * 생성 시각으로 대신한다(같은 밀리초에 교체되면 구별하지 못하지만, 이름·모양 검사보다는 강하다).
 */
private fun File.identity(): Any? = runCatching {
    val attributes = Files.readAttributes(toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    attributes.fileKey() ?: attributes.creationTime()
}.getOrNull()

/**
 * 이 경로가 [prepareStaging] 이 만든 **그** 디렉터리로 남아 있는지.
 *
 * 이름·모양 검사는 흉내 낼 수 있으므로 만든 시점의 지문([File.identity])까지 대조한다 —
 * 같은 이름으로 새로 만든 디렉터리는 지문이 달라 걸린다.
 */
private fun isAppOwnedStaging(staging: File, createdIdentity: Any?): Boolean {
    val path = staging.toPath()
    val looksRight = staging.name.startsWith(STAGING_PREFIX) &&
        !Files.isSymbolicLink(path) &&
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    // 지문을 못 잡았다면 앱 소유를 증명할 수 없다 — 증명하지 못한 것은 지우지 않는다.
    return looksRight && createdIdentity != null && createdIdentity == staging.identity()
}

/**
 * 링크를 따라가지 않는 재귀 삭제. `File.deleteRecursively` 는 `listFiles()` 로 훑어 **디렉터리
 * 심볼릭 링크 안까지 들어가** 그 내용을 지운다 — 스테이징 안에 링크가 심어지면 링크가 가리키는
 * 사용자 데이터가 사라진다. `walkFileTree` 는 기본이 링크 미추적이라 링크는 링크로만 지운다.
 */
private fun deleteTreeWithoutFollowingLinks(root: File): Boolean = runCatching {
    Files.walkFileTree(
        root.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                failure?.let { throw it }
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}.isSuccess

/** 같은 파일 시스템이면 원자적으로, 아니면 일반 이동으로 옮긴다. 대상이 있으면 덮어쓰지 않고 실패한다. */
private fun moveDirectory(staging: Path, target: Path) {
    try {
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        // 파일 시스템 경계를 넘는 이동은 원자적일 수 없다. 덮어쓰기 옵션은 여전히 주지 않는다.
        Files.move(staging, target).also { logMovedNonAtomically(unsupported) }
    }
}

/** 비어 있는 디렉터리인지. 디렉터리가 아니면 `list()` 가 `null` 이라 비어 있지 않은 것으로 본다. */
private fun File.isEmptyDirectory(): Boolean = list()?.isEmpty() == true

/** 원자적 이동이 안 되는 경계를 넘었다는 사실만 남긴다 — 실패가 아니라 경로 선택의 결과다. */
private fun logMovedNonAtomically(cause: AtomicMoveNotSupportedException) {
    LOGGER.log(Level.FINE, "staging move fell back to non-atomic", cause)
}
