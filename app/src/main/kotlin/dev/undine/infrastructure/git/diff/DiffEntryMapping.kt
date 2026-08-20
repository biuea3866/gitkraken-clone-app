package dev.undine.infrastructure.git.diff

import dev.undine.domain.ChangeType
import dev.undine.domain.FileChange
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.patch.FileHeader

internal fun fileChangesOf(formatter: DiffFormatter, entries: List<DiffEntry>): List<FileChange> =
    entries.map { entry -> fileChangeOf(formatter, entry) }

/**
 * `DiffEntry` 를 도메인 [FileChange] 로 옮긴다.
 *
 * 증감 줄 수만 세고 **hunk 본문은 만들지 않는다** — 목록 렌더링 시점에 전체 diff 를 만들면
 * 대형 저장소가 멈춘다. hunk 는 `DiffGateway.hunksOf` 가 파일 하나에 대해서만 계산한다.
 */
internal fun fileChangeOf(formatter: DiffFormatter, entry: DiffEntry): FileChange {
    val header = formatter.toFileHeader(entry)
    val edits = header.toEditList()
    return FileChange(
        path = pathOf(entry),
        previousPath = previousPathOf(entry),
        changeType = changeTypeOf(entry.changeType),
        addedLines = edits.sumOf { edit -> edit.endB - edit.beginB },
        deletedLines = edits.sumOf { edit -> edit.endA - edit.beginA },
        isBinary = header.patchType != FileHeader.PatchType.UNIFIED,
    )
}

/** 삭제는 이전 경로가, 그 외(rename 포함)는 새 경로가 파일을 가리킨다. */
internal fun pathOf(entry: DiffEntry): String =
    if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath

private fun previousPathOf(entry: DiffEntry): String? =
    entry.oldPath.takeIf {
        entry.changeType == DiffEntry.ChangeType.RENAME || entry.changeType == DiffEntry.ChangeType.COPY
    }

private fun changeTypeOf(raw: DiffEntry.ChangeType): ChangeType =
    when (raw) {
        DiffEntry.ChangeType.ADD -> ChangeType.ADDED
        DiffEntry.ChangeType.MODIFY -> ChangeType.MODIFIED
        DiffEntry.ChangeType.DELETE -> ChangeType.DELETED
        DiffEntry.ChangeType.RENAME -> ChangeType.RENAMED
        DiffEntry.ChangeType.COPY -> ChangeType.COPIED
    }
