package dev.undine.infrastructure.git.diff

import dev.undine.domain.DiffResult
import dev.undine.domain.FileComparison
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.util.io.DisabledOutputStream

/** 두 파일 이력 시점의 트리를 직접 비교하는 구현 세부. */
internal fun Repository.hunksBetween(comparison: FileComparison): DiffResult =
    newObjectReader().use { reader ->
        RevWalk(reader).use { walk ->
            val before = walk.parseCommitOf(comparison.before)
            val after = walk.parseCommitOf(comparison.after)
            val entry = scanForComparison(
                oldTree = treeParserOf(reader, before.tree),
                newTree = treeParserOf(reader, after.tree),
            ) { entries ->
                entries.firstOrNull { found ->
                    found.oldPath == comparison.beforePath && found.newPath == comparison.afterPath
                }
            }
            entry?.let { found -> hunksOfEntry(reader, found) } ?: hunksOfBlobs(
                reader = reader,
                oldBlob = findBlobInTree(comparison.beforePath, before.tree),
                newBlob = findBlobInTree(comparison.afterPath, after.tree),
            )
        }
    }

private fun <T> Repository.scanForComparison(
    oldTree: AbstractTreeIterator,
    newTree: AbstractTreeIterator,
    block: (List<DiffEntry>) -> T,
): T = DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
    formatter.setRepository(this)
    formatter.setDetectRenames(true)
    formatter.setPathFilter(TreeFilter.ALL)
    block(formatter.scan(oldTree, newTree))
}

private fun Repository.findBlobInTree(path: String, tree: AnyObjectId): ObjectId? =
    TreeWalk.forPath(this, path, tree)?.use { walk -> walk.getObjectId(0) }
