package dev.undine.infrastructure.git.submodule

import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import java.io.IOException
import java.time.Instant

/** 인덱스에 없는 경로를 지우는 것은 무해한 no-op 이다 — 롤백에서 그대로 부를 수 있다. */
internal fun Repository.removeIndexEntry(path: String) {
    editDirCache(path) { editor -> editor.add(DirCacheEditor.DeletePath(path)) }
}

/**
 * 인덱스 엔트리 하나를 그대로 담아 둔 스냅샷. 되돌리기가 워킹트리만 복원하면 호출 전 **스테이징
 * 상태**가 바뀐다 — unstaged 였던 수정이 강제로 stage 되거나, 인덱스에만 있던 엔트리가 사라진다.
 * 엔트리가 애초에 없었다는 사실은 이 타입이 아니라 null 이 표현하고, 그때는 [removeIndexEntry] 가 맡는다.
 */
internal class IndexEntrySnapshot(
    val path: String,
    private val objectId: ObjectId,
    private val fileMode: FileMode,
    private val length: Int,
    private val lastModified: Instant,
) {

    /** 스냅샷 시점의 blob·모드·크기·시각을 그대로 되돌린다. blob 은 이미 객체 DB 에 있다. */
    internal fun asPathEdit(): DirCacheEditor.PathEdit = object : DirCacheEditor.PathEdit(path) {
        override fun apply(entry: DirCacheEntry) {
            entry.fileMode = fileMode
            entry.setObjectId(objectId)
            entry.setLength(length)
            entry.setLastModified(lastModified)
        }
    }
}

/** 인덱스에 그 경로가 없으면 null — "엔트리가 없었다" 는 것도 복원해야 할 상태다. */
internal fun Repository.readIndexEntry(path: String): IndexEntrySnapshot? =
    readDirCache().getEntry(path)?.let { entry ->
        IndexEntrySnapshot(
            path = entry.pathString,
            objectId = entry.objectId,
            fileMode = entry.fileMode,
            length = entry.length,
            lastModified = entry.lastModifiedInstant,
        )
    }

internal fun Repository.restoreIndexEntry(snapshot: IndexEntrySnapshot) {
    editDirCache(snapshot.path) { editor -> editor.add(snapshot.asPathEdit()) }
}

/** 원래 엔트리가 있었으면 그 값으로, 없었으면 부재로 되돌린다 — "없었다" 도 복원해야 할 상태다. */
internal fun Repository.restoreIndexEntry(path: String, snapshot: IndexEntrySnapshot?) {
    snapshot?.let(::restoreIndexEntry) ?: removeIndexEntry(path)
}

private fun Repository.editDirCache(path: String, edit: (DirCacheEditor) -> Unit) {
    val cache = lockDirCache()
    try {
        val editor = cache.editor()
        edit(editor)
        if (!editor.commit()) throw IOException("인덱스를 갱신하지 못했습니다: '$path'")
    } finally {
        // commit 이 성공하면 잠금은 이미 풀려 있어 두 번 풀어도 무해하다.
        cache.unlock()
    }
}
