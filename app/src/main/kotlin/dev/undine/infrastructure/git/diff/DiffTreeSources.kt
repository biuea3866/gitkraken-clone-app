package dev.undine.infrastructure.git.diff

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator

internal fun treeParserOf(reader: ObjectReader, treeId: AnyObjectId): AbstractTreeIterator =
    CanonicalTreeParser(null, reader, treeId)

/**
 * 비교 기준이 되는 부모 트리. 부모가 없는 최초 커밋은 **빈 트리**와 비교하므로 전체 추가가 된다.
 *
 * 범위 밖 `parentIndex`(음수 · 부모 수 이상 · 최초 커밋에서 0 이외)는 호출부 버그이므로
 * 도메인 예외가 아니라 `IllegalArgumentException` 이다 (wave 2 결정 A7).
 */
internal fun parentTreeOf(
    walk: RevWalk,
    reader: ObjectReader,
    target: RevCommit,
    parentIndex: Int,
): AbstractTreeIterator {
    require(parentIndex >= 0) { "parentIndex 는 0 이상이어야 합니다: $parentIndex" }
    require(parentIndex < maxOf(target.parentCount, 1)) {
        "parentIndex 가 부모 수(${target.parentCount}) 범위를 벗어났습니다: $parentIndex"
    }
    if (target.parentCount == 0) return EmptyTreeIterator()
    return treeParserOf(reader, walk.parseCommit(target.getParent(parentIndex)).tree)
}

/** HEAD 트리. 커밋이 아직 없는 저장소는 빈 트리라서 인덱스 전체가 추가로 보고된다. */
internal fun headTreeOf(repository: Repository, reader: ObjectReader): AbstractTreeIterator {
    val head = repository.resolve(Constants.HEAD) ?: return EmptyTreeIterator()
    return RevWalk(reader).use { walk -> treeParserOf(reader, walk.parseCommit(head).tree) }
}

internal fun RevWalk.parseCommitOf(commit: CommitId): RevCommit {
    val id = ObjectId.fromString(commit.value)
    if (!objectReader.has(id)) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
    }
    return parseCommit(id)
}
