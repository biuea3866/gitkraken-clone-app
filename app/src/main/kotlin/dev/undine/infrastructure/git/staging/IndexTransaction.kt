package dev.undine.infrastructure.git.staging

import dev.undine.domain.UndineException
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository

private const val OPERATION_WRITE_INDEX = "staging.writeIndex"
private const val OPERATION_RESTORE_INDEX = "staging.restoreIndex"

/**
 * 인덱스를 여러 단계로 바꾸는 [block] 을 **전부 적용되거나 전혀 적용되지 않게** 만든다.
 *
 * `add` 와 `rm --cached` 는 각자 DirCache 를 잠그는 별개의 단계라, 뒤 단계가 실패하면 앞 단계만
 * 남아 사용자가 고르지 않은 부분 스테이징 상태가 된다. 실패하면 시작 시점 항목으로 되돌리고
 * 원래 실패를 그대로 올린다 — 복원 자체가 실패하면 원인을 가리지 않도록 suppressed 로 덧붙인다.
 */
@Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
internal fun <T> Repository.withIndexRestoredOnFailure(block: () -> T): T {
    val snapshot = indexEntries()
    return try {
        block()
    } catch (failure: Exception) {
        runCatching { commitIndex(snapshot, OPERATION_RESTORE_INDEX) }
            .onFailure { restoreFailure -> failure.addSuppressed(restoreFailure) }
        throw failure
    }
}

/** 인덱스 항목 하나만 새 blob 으로 갈아 끼운다. 워킹트리는 건드리지 않는다. */
internal fun Repository.writeIndexEntry(path: String, content: ByteArray, fileMode: FileMode) {
    val blobId = newObjectInserter().use { inserter ->
        inserter.insert(Constants.OBJ_BLOB, content).also { inserter.flush() }
    }
    val replacement = DirCacheEntry(path).apply {
        setFileMode(fileMode)
        setObjectId(blobId)
        setLength(content.size)
    }
    commitIndex(indexEntries().filter { entry -> entry.pathString != path } + replacement, OPERATION_WRITE_INDEX)
}

internal fun Repository.readBlob(objectId: ObjectId): String =
    newObjectReader().use { reader ->
        reader.open(objectId, Constants.OBJ_BLOB).bytes.toString(Charsets.UTF_8)
    }

/** 잠금 없이 현재 인덱스 항목을 읽는다. 복원과 부분 갱신이 같은 스냅샷 형태를 쓴다. */
private fun Repository.indexEntries(): List<DirCacheEntry> =
    readDirCache().let { dirCache -> (0 until dirCache.entryCount).map(dirCache::getEntry) }

/**
 * 인덱스를 [entries] 로 갈아 끼운다.
 * 실패해도 `index.lock` 을 남기면 이후 모든 git 연산이 막히므로 반드시 해제한다.
 */
private fun Repository.commitIndex(entries: List<DirCacheEntry>, operation: String) {
    val dirCache = lockDirCache()
    val committed = runCatching {
        val builder = dirCache.builder()
        entries.forEach(builder::add)
        builder.commit()
    }.onFailure { dirCache.unlock() }.getOrThrow()
    if (!committed) {
        dirCache.unlock()
        throw UndineException.GitOperationFailed(operation)
    }
}
