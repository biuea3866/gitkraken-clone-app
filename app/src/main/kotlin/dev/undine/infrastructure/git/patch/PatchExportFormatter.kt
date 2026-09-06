package dev.undine.infrastructure.git.patch

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import dev.undine.domain.patch.CommitPatch
import dev.undine.domain.patch.CommitRange
import dev.undine.domain.patch.PatchExport
import dev.undine.domain.patch.WorkingTreeScope
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.NotIgnoredFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 워킹트리 비교에서 `FileTreeIterator` 가 놓이는 자리 — 무시된 파일을 걸러낼 때 필요하다. */
private const val WORKING_TREE_INDEX = 1

/**
 * 커밋 범위를 패치로 내보낸다. **커밋별과 통합본을 한 번의 순회로 함께** 만든다 —
 * 호출자가 용도마다 다시 부르게 하지 않는다.
 *
 * rename 탐지를 켜지 않는다. 켜면 내보낸 패치에 rename 항목이 생겨 역방향 적용이 곧바로
 * 미지원이 된다 — 생성 쪽의 편의가 적용 쪽의 계약을 좁히는 것은 맞바꿀 이유가 없다.
 */
internal fun Repository.exportRange(range: CommitRange): PatchExport =
    RevWalk(this).use { walk ->
        val to = walk.parseCommitOf(range.to)
        val from = range.from?.let { commit -> walk.parseCommitOf(commit) }
        walk.markStart(to)
        from?.let(walk::markUninteresting)
        val commits = walk.toList().reversed()
        newObjectReader().use { reader ->
            PatchExport(
                perCommit = commits.map { commit ->
                    CommitPatch(CommitId.of(commit.name), patchOf(walk, reader, commit))
                },
                combined = formatDiff(
                    oldTree = from?.let { parent -> reader.treeParserOf(parent.tree) } ?: EmptyTreeIterator(),
                    newTree = reader.treeParserOf(to.tree),
                    pathFilter = TreeFilter.ALL,
                ),
            )
        }
    }

/**
 * 워킹트리·인덱스 변경을 패치로 내보낸다. 커밋이 없으므로 `perCommit` 은 비고 통합본만 채워진다 —
 * 빈 목록은 "커밋 단위로 나눌 것이 없다" 는 사실 그대로다.
 */
internal fun Repository.exportScope(scope: WorkingTreeScope): PatchExport =
    newObjectReader().use { reader ->
        val combined = when (scope) {
            WorkingTreeScope.STAGED -> formatDiff(
                oldTree = reader.headTreeParser(this),
                newTree = DirCacheIterator(readDirCache()),
                pathFilter = TreeFilter.ALL,
            )

            WorkingTreeScope.UNSTAGED -> formatDiff(
                oldTree = DirCacheIterator(readDirCache()),
                newTree = FileTreeIterator(this),
                pathFilter = NotIgnoredFilter(WORKING_TREE_INDEX),
            )

            WorkingTreeScope.ALL -> formatDiff(
                oldTree = reader.headTreeParser(this),
                newTree = FileTreeIterator(this),
                pathFilter = NotIgnoredFilter(WORKING_TREE_INDEX),
            )
        }
        PatchExport(perCommit = emptyList(), combined = combined)
    }

/** 루트 커밋은 부모가 없으므로 빈 트리와 비교한다 — 첫 커밋도 내보낼 수 있어야 한다. */
private fun Repository.patchOf(walk: RevWalk, reader: ObjectReader, commit: RevCommit): ByteArray {
    val parent = commit.takeIf { child -> child.parentCount > 0 }
        ?.let { child -> walk.parseCommit(child.getParent(0)) }
    return formatDiff(
        oldTree = parent?.let { found -> reader.treeParserOf(found.tree) } ?: EmptyTreeIterator(),
        newTree = reader.treeParserOf(commit.tree),
        pathFilter = TreeFilter.ALL,
    )
}

private fun Repository.formatDiff(
    oldTree: AbstractTreeIterator,
    newTree: AbstractTreeIterator,
    pathFilter: TreeFilter,
): ByteArray {
    val out = ByteArrayOutputStream()
    DiffFormatter(out).use { formatter ->
        formatter.setRepository(this)
        formatter.setPathFilter(pathFilter)
        formatter.format(formatter.scan(oldTree, newTree))
    }
    return out.toByteArray()
}

private fun ObjectReader.treeParserOf(treeId: ObjectId): AbstractTreeIterator =
    CanonicalTreeParser(null, this, treeId)

/** 커밋이 없는 저장소에서도 내보낼 수 있어야 한다 — HEAD 가 없으면 빈 트리가 기준이다. */
private fun ObjectReader.headTreeParser(repository: Repository): AbstractTreeIterator =
    repository.resolve(Constants.HEAD + "^{tree}")?.let { tree -> treeParserOf(tree) } ?: EmptyTreeIterator()

/** [CommitId] 가 40자 hexadecimal 을 이미 보장하므로 `fromString` 은 여기서 실패하지 않는다. */
private fun RevWalk.parseCommitOf(commit: CommitId): RevCommit =
    try {
        parseCommit(ObjectId.fromString(commit.value))
    } catch (failure: IOException) {
        throw failure.asCommitLookupFailure(commit)
    }

/**
 * **없는 커밋과 못 읽은 저장소를 가른다.** 대상이 없거나 커밋이 아니라는 것만 [UndineException.NotFound]
 * 다 — 그 밖의 읽기 실패(손상된 객체·I/O)는 **그대로 올려** `patchOperation` 이
 * `GitOperationFailed` 로 번역하게 둔다. 전부 `NotFound` 로 접으면 손상된 저장소가
 * "그런 커밋 없음" 으로 보고돼 사용자가 원인을 영영 알 수 없다.
 */
private fun IOException.asCommitLookupFailure(commit: CommitId): Throwable =
    when (this) {
        is MissingObjectException, is IncorrectObjectTypeException ->
            UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)

        else -> this
    }
