package dev.undine.infrastructure.git.diff

import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.DiffResult
import dev.undine.domain.FileChange
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.NotIgnoredFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.util.io.DisabledOutputStream

/** 워킹트리 비교에서 `FileTreeIterator` 가 놓이는 TreeWalk 자리 — 무시된 파일을 걸러낼 때 필요하다. */
private const val WORKING_TREE_INDEX = 1

/**
 * [DiffGateway] 의 JGit 구현.
 *
 * **지연 계산이 원칙이다.** `changedFiles*` 는 경로·변경 종류·증감 줄 수만 내고,
 * hunk 본문은 [hunksOf] 가 선택된 파일 하나에 대해서만 만든다.
 * 이진 파일과 1 MiB 초과 텍스트는 내용을 읽지 않고 `NotComputed` 사유를 올린다 —
 * 조용히 빈 diff 를 돌려주면 "변경 없음" 으로 오해된다.
 *
 * JGit `Repository` 는 스레드 안전하지 않으므로 **핸들에 직접 손대지 않고 [GitAccess] 를 통해서만**
 * 접근한다 (wave 2 정정 C1). 직렬화와 `Dispatchers.IO` 실행이 거기 모여 있어 다른 Gateway 구현과
 * 같은 경계를 공유한다 — 이 클래스는 락도 `withContext` 도 다시 걸지 않고, 핸들을 닫지도 않는다.
 * 조회 단위로 여는 JGit 자원은 모두 `use {}` 로 닫는다.
 */
class DiffGatewayImpl(private val gitAccess: GitAccess) : DiffGateway {

    override suspend fun changedFiles(commit: CommitId, parentIndex: Int): List<FileChange> =
        diffOperation("changedFiles") { repository -> repository.changedFilesIn(commit, parentIndex) }

    override suspend fun changedFilesStaged(): List<FileChange> =
        diffOperation("changedFilesStaged") { repository ->
            repository.newObjectReader().use { reader ->
                repository.scan(
                    oldTree = headTreeOf(repository, reader),
                    newTree = DirCacheIterator(repository.readDirCache()),
                    pathFilter = TreeFilter.ALL,
                ) { formatter, entries -> fileChangesOf(formatter, entries) }
            }
        }

    override suspend fun changedFilesUnstaged(): List<FileChange> =
        diffOperation("changedFilesUnstaged") { repository ->
            repository.scan(
                oldTree = DirCacheIterator(repository.readDirCache()),
                newTree = FileTreeIterator(repository),
                pathFilter = NotIgnoredFilter(WORKING_TREE_INDEX),
            ) { formatter, entries -> fileChangesOf(formatter, entries) }
        }

    /**
     * rename 된 파일은 **새 경로**(`FileChange.path`)로 조회한다 (wave 2 결정).
     * 이 커밋에서 바뀌지 않은 경로는 hunk 가 없으므로 `Computed(emptyList())` 다.
     */
    override suspend fun hunksOf(commit: CommitId, path: String, parentIndex: Int): DiffResult =
        diffOperation("hunksOf") { repository -> repository.hunksIn(commit, path, parentIndex) }

    private fun Repository.changedFilesIn(commit: CommitId, parentIndex: Int): List<FileChange> =
        newObjectReader().use { reader ->
            RevWalk(reader).use { walk ->
                val target = walk.parseCommitOf(commit)
                scan(
                    oldTree = parentTreeOf(walk, reader, target, parentIndex),
                    newTree = treeParserOf(reader, target.tree),
                    pathFilter = TreeFilter.ALL,
                ) { formatter, entries -> fileChangesOf(formatter, entries) }
            }
        }

    private fun Repository.hunksIn(commit: CommitId, path: String, parentIndex: Int): DiffResult =
        newObjectReader().use { reader ->
            RevWalk(reader).use { walk ->
                val target = walk.parseCommitOf(commit)
                val entry = scan(
                    oldTree = parentTreeOf(walk, reader, target, parentIndex),
                    newTree = treeParserOf(reader, target.tree),
                    pathFilter = TreeFilter.ALL,
                ) { _, entries -> entries.firstOrNull { entry -> pathOf(entry) == path } }
                entry?.let { found -> hunksOfEntry(reader, found) } ?: DiffResult.Computed(emptyList())
            }
        }

    /**
     * rename 탐지를 켠 채로 두 트리를 훑는다. 경로 필터는 rename 탐지 전에 적용되므로
     * 단일 파일을 고를 때도 필터로 좁히지 않고 전체 스캔 결과에서 찾는다 —
     * 좁히면 이름만 바뀐 파일이 삭제·추가 2건으로 보인다.
     */
    private fun <T> Repository.scan(
        oldTree: AbstractTreeIterator,
        newTree: AbstractTreeIterator,
        pathFilter: TreeFilter,
        block: (DiffFormatter, List<DiffEntry>) -> T,
    ): T =
        DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
            formatter.setRepository(this)
            formatter.setDetectRenames(true)
            formatter.setPathFilter(pathFilter)
            block(formatter, formatter.scan(oldTree, newTree))
        }

    /** 모든 진입점이 지나는 단 하나의 통로다 — 여기 밖에서 `Repository` 를 만지면 직렬화가 깨진다. */
    private suspend fun <T> diffOperation(operation: String, block: (Repository) -> T): T =
        gitAccess.withRepository { repository ->
            runCatching { block(repository) }
                .getOrElse { failure -> translateDiffFailure(operation, failure) }
        }
}
