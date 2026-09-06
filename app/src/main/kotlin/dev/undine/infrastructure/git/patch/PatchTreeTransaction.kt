package dev.undine.infrastructure.git.patch

import dev.undine.domain.UndineException
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import java.nio.file.Files
import java.nio.file.LinkOption

private const val OPERATION_RESTORE_INDEX = "patch.restoreIndex"

/**
 * 패치 적용 전 상태를 담은 **Git 트리 트랜잭션**.
 *
 * 파일 경로 목록을 손으로 스냅샷·복원하지 않는 것이 이 티켓의 핵심이다. 트리는 mode·심볼릭 링크·
 * 디렉터리 구조를 그 자체로 담으므로, 복원할 때 열거할 항목이 없다 — 기록한 트리를 그대로 다시
 * 펼치면 된다. 삭제 순서·조상 정리·파일↔디렉터리 전환 같은 개념이 사라진다.
 */
internal class PatchTreeTransaction(
    private val repository: Repository,
    val paths: List<String>,
    val treeId: ObjectId,
    private val indexSnapshot: List<DirCacheEntry>,
) {

    /** 기록한 트리로 워킹트리와 인덱스를 되돌린다. */
    fun restore() {
        repository.materialize(treeId, paths)
        repository.replaceIndexEntriesUnder(paths, indexSnapshot)
    }
}

/**
 * [paths] 아래의 **적용 전 추적 상태**를 트리 객체로 기록한다.
 *
 * 인덱스에 있는 경로만 담는다 — 추적되지 않은 파일은 트리로 복원할 수 없기 때문이고, 그런 파일이
 * 대상에 있으면 [requireRestorableTarget] 이 애초에 적용을 거부한다.
 *
 * 내용과 mode 는 **인덱스가 아니라 워킹트리**에서 읽는다. 인덱스에서 읽으면 스테이징하지 않은
 * 수정과 실행 권한 변경이 복원 시점에 사라진다.
 */
internal fun Repository.recordPatchTree(paths: List<String>, inserter: ObjectInserter): PatchTreeTransaction {
    val cache = DirCache.newInCore()
    val builder = cache.builder()
    if (paths.isNotEmpty()) {
        TreeWalk(this).use { walk ->
            walk.addTree(DirCacheIterator(readDirCache()))
            walk.addTree(FileTreeIterator(this))
            walk.isRecursive = true
            walk.filter = PathFilterGroup.createFromStrings(paths)
            while (walk.next()) {
                val recorded = recordedEntry(walk, inserter) ?: continue
                builder.add(recorded)
            }
        }
    }
    builder.finish()
    val treeId = cache.writeTree(inserter)
    inserter.flush()
    return PatchTreeTransaction(this, paths, treeId, indexEntriesUnder(paths))
}

/**
 * 추적되면서 워킹트리에도 있는 항목만 기록한다.
 *
 * 추적되지만 워킹트리에서 지워진 파일은 담지 않는다 — 담으면 복원이 사용자가 지운 파일을 되살린다.
 */
private fun recordedEntry(walk: TreeWalk, inserter: ObjectInserter): DirCacheEntry? {
    val tracked = walk.getTree(0, DirCacheIterator::class.java)
    val working = walk.getTree(1, WorkingTreeIterator::class.java)
    if (tracked == null || working == null) return null
    val content = working.openEntryStream().use { stream -> stream.readBytes() }
    return DirCacheEntry(walk.pathString).apply {
        fileMode = working.entryFileMode
        setObjectId(inserter.insert(Constants.OBJ_BLOB, content))
        setLength(content.size)
    }
}

/**
 * 트랜잭션이 실제로 책임져야 할 경로 — 패치가 지목한 경로 + **워킹트리에 파일로 존재하는 조상**.
 *
 * 조상을 함께 담는 이유: `a` 가 파일인데 패치가 `a/b` 를 만들면 승격은 `a` 를 지워야 한다.
 * 그 사실을 트랜잭션이 모르면 기록 트리에 `a` 가 빠져 복원할 수 없고, `a` 가 추적되지 않은
 * 파일이면 조용히 사라진다. 담아 두면 기록·복원 대상이 되고, 담을 수 없으면
 * [requireRestorableTarget] 이 거부한다.
 */
internal fun Repository.transactionPathsFor(paths: List<String>): List<String> {
    val root = workTreeRoot()
    val ancestors = paths.flatMap { path -> path.ancestorPaths() }
        .distinct()
        .filter { ancestor ->
            val candidate = root.resolve(ancestor)
            Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
        }
    return (paths + ancestors).distinct()
}

internal fun String.ancestorPaths(): List<String> =
    split('/').dropLast(1).runningReduce { parent, segment -> "$parent/$segment" }

/** [paths] 아래의 인덱스 항목. 접두 일치이므로 디렉터리 경로도 그 아래 전부를 집는다. */
internal fun Repository.indexEntriesUnder(paths: List<String>): List<DirCacheEntry> {
    if (paths.isEmpty()) return emptyList()
    val prefixes = paths.map { path -> path.trimEnd('/') }
    return readDirCache().let { cache -> (0 until cache.entryCount).map(cache::getEntry) }
        .filter { entry -> prefixes.any { prefix -> entry.pathString.isUnder(prefix) } }
}

/** [treeId] 를 인덱스에 반영한다. [paths] 밖의 항목은 그대로 둔다 — 남의 스테이징을 건드리지 않는다. */
internal fun Repository.writeIndexEntriesFrom(treeId: ObjectId, paths: List<String>) {
    val replacements = readTreeBlobs(treeId, paths).map { (path, blob) ->
        DirCacheEntry(path).apply {
            fileMode = blob.fileMode
            setObjectId(blob.blobId)
        }
    }
    replaceIndexEntriesUnder(paths, replacements)
}

/**
 * [paths] 아래의 인덱스 항목을 [replacements] 로 갈아 끼운다.
 *
 * 실패해도 `index.lock` 을 남기면 이후 모든 git 연산이 막히므로 반드시 해제한다.
 */
internal fun Repository.replaceIndexEntriesUnder(paths: List<String>, replacements: List<DirCacheEntry>) {
    val prefixes = paths.map { path -> path.trimEnd('/') }
    val kept = readDirCache().let { cache -> (0 until cache.entryCount).map(cache::getEntry) }
        .filterNot { entry -> prefixes.any { prefix -> entry.pathString.isUnder(prefix) } }
    val dirCache = lockDirCache()
    val committed = runCatching {
        val builder = dirCache.builder()
        (kept + replacements).sortedBy(DirCacheEntry::getPathString).forEach(builder::add)
        builder.commit()
    }.onFailure { dirCache.unlock() }.getOrThrow()
    if (!committed) {
        dirCache.unlock()
        throw UndineException.GitOperationFailed(OPERATION_RESTORE_INDEX)
    }
}

/** `a` 는 `a` 와 `a/b` 를 집지만 `ab` 는 집지 않는다 — 문자열 접두사만 보면 형제 경로가 딸려 온다. */
private fun String.isUnder(prefix: String): Boolean = this == prefix || startsWith("$prefix/")
