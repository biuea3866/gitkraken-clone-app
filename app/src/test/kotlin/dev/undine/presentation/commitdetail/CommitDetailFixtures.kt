package dev.undine.presentation.commitdetail

import dev.undine.application.commitdetail.LoadChangedFilesUseCase
import dev.undine.domain.ChangeType
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.DiffResult
import dev.undine.domain.FileComparison
import dev.undine.domain.FileChange
import dev.undine.domain.Person
import dev.undine.domain.UndineException
import java.time.Instant

internal val TARGET_COMMIT = CommitId.of("1".repeat(40))
internal val FIRST_PARENT = CommitId.of("2".repeat(40))
internal val SECOND_PARENT = CommitId.of("3".repeat(40))

internal val AUTHORED_AT: Instant = Instant.parse("2026-03-04T05:06:07Z")
internal val COMMITTED_AT: Instant = Instant.parse("2026-03-05T06:07:08Z")

internal val AUTHOR = Person(name = "Hana Kim", email = "hana@undine.dev")
internal val COMMITTER = Person(name = "Doori Lee", email = "doori@undine.dev")

internal fun commitOf(
    id: CommitId = TARGET_COMMIT,
    parents: List<CommitId> = listOf(FIRST_PARENT),
    message: String = "커밋 제목",
    author: Person = AUTHOR,
    committer: Person = AUTHOR,
): Commit = Commit(
    id = id,
    parents = parents,
    message = message,
    author = author,
    committer = committer,
    authoredAt = AUTHORED_AT,
    committedAt = COMMITTED_AT,
)

internal fun fileChangeOf(
    path: String,
    changeType: ChangeType = ChangeType.MODIFIED,
    previousPath: String? = null,
    addedLines: Int = 2,
    deletedLines: Int = 1,
): FileChange = FileChange(
    path = path,
    previousPath = previousPath,
    changeType = changeType,
    addedLines = addedLines,
    deletedLines = deletedLines,
    isBinary = false,
)

/**
 * 이진 파일 변경. 줄 수 개념이 없으므로 증감은 0 이다 — 화면은 이 조합에 수치 대신 binary 문구를 낸다.
 * [fileChangeOf] 에 플래그를 더하지 않고 따로 두는 것은, 호출부에서 "이진 파일" 이 이름으로 읽히게 하려는 것이다.
 */
internal fun binaryFileChangeOf(
    path: String,
    changeType: ChangeType = ChangeType.MODIFIED,
): FileChange = FileChange(
    path = path,
    previousPath = null,
    changeType = changeType,
    addedLines = 0,
    deletedLines = 0,
    isBinary = true,
)

/**
 * 부모 인덱스별로 다른 목록을 돌려주는 [DiffGateway] 대역.
 *
 * [hunksOf] 는 호출되면 실패한다 — 상세 패널이 hunk 를 요청하지 않는다는 요구를
 * 테스트 대역이 직접 강제한다.
 */
internal class FakeDiffGateway(
    private val filesByParentIndex: Map<Int, List<FileChange>> = emptyMap(),
    private val failure: UndineException? = null,
) : DiffGateway {

    val requestedParentIndexes: MutableList<Int> = mutableListOf()

    override suspend fun changedFiles(commit: CommitId, parentIndex: Int): List<FileChange> {
        requestedParentIndexes += parentIndex
        failure?.let { throw it }
        return filesByParentIndex[parentIndex].orEmpty()
    }

    override suspend fun changedFilesStaged(): List<FileChange> =
        error("상세 패널은 스테이징 변경을 조회하지 않는다")

    override suspend fun changedFilesUnstaged(): List<FileChange> =
        error("상세 패널은 워킹트리 변경을 조회하지 않는다")

    override suspend fun hunksOf(commit: CommitId, path: String, parentIndex: Int): DiffResult =
        error("상세 패널은 hunk 를 요청하지 않는다")

    override suspend fun hunksBetween(comparison: FileComparison): DiffResult =
        error("상세 패널은 두 이력 시점 diff 를 요청하지 않는다")
}

internal fun useCaseOf(gateway: DiffGateway): LoadChangedFilesUseCase = LoadChangedFilesUseCase(gateway)
