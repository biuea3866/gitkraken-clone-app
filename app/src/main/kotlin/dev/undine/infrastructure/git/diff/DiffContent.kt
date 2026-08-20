package dev.undine.infrastructure.git.diff

import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader

private val EMPTY_CONTENT = ByteArray(0)

/**
 * 선택한 파일 **하나**의 hunk 를 계산한다.
 *
 * 판정 순서가 곧 비용 순서다 — 이진 판정은 앞 8 KiB 만 보고, 크기 확인은 blob 헤더만 읽는다.
 * 내용을 통째로 읽는 것은 실제로 hunk 를 만드는 마지막 분기뿐이다.
 * 이진 판정은 JGit(`RawText.isBinary`)의 판정을 그대로 쓴다.
 */
internal fun hunksOfEntry(reader: ObjectReader, entry: DiffEntry): DiffResult {
    val oldBlob = blobIdOf(entry.oldId, entry.oldMode)
    val newBlob = blobIdOf(entry.newId, entry.newMode)
    val sides = listOfNotNull(oldBlob, newBlob)
    return when {
        sides.any { blob -> reader.isBinaryBlob(blob) } ->
            DiffResult.NotComputed(DiffResult.Reason.BINARY)

        sides.any { blob -> reader.getObjectSize(blob, Constants.OBJ_BLOB) > MAX_DIFF_BLOB_BYTES } ->
            DiffResult.NotComputed(DiffResult.Reason.TOO_LARGE)

        else -> DiffResult.Computed(computeHunks(reader, oldBlob, newBlob))
    }
}

private fun computeHunks(reader: ObjectReader, oldBlob: ObjectId?, newBlob: ObjectId?): List<DiffHunk> {
    val oldText = reader.rawTextOf(oldBlob)
    val newText = reader.rawTextOf(newBlob)
    val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, oldText, newText)
    return HunkBuilder.build(oldText, newText, edits)
}

/** 한쪽에만 있는 변경(추가·삭제)은 반대편 blob 이 없다. */
private fun blobIdOf(id: AbbreviatedObjectId?, mode: FileMode): ObjectId? =
    id?.takeIf { mode != FileMode.MISSING }
        ?.toObjectId()
        ?.takeIf { blob -> blob != ObjectId.zeroId() }

private fun ObjectReader.rawTextOf(blob: ObjectId?): RawText =
    RawText(blob?.let { id -> open(id, Constants.OBJ_BLOB).cachedBytes } ?: EMPTY_CONTENT)

private fun ObjectReader.isBinaryBlob(blob: ObjectId): Boolean =
    open(blob, Constants.OBJ_BLOB).openStream().use { stream -> RawText.isBinary(stream) }
