package dev.undine.domain

/**
 * 파일 이력에서 선택한 두 스냅샷. 두 커밋은 부모-자식 관계일 필요가 없고, rename 전후 경로도 각각
 * 독립적으로 담는다. 따라서 `parentIndex` 로는 이 요청을 표현할 수 없다.
 */
data class FileComparison(
    val before: CommitId,
    val beforePath: String,
    val after: CommitId,
    val afterPath: String,
) {
    init {
        require(beforePath.isNotBlank()) { "비교 전 파일 경로가 비어 있습니다" }
        require(afterPath.isNotBlank()) { "비교 후 파일 경로가 비어 있습니다" }
    }
}

/** 변경 파일 목록과 hunk 계산. [changedFiles] 의 `parentIndex` 는 병합 커밋에서 비교할 부모를 고른다. */
interface DiffGateway {

    suspend fun changedFiles(commit: CommitId, parentIndex: Int): List<FileChange>

    suspend fun changedFilesStaged(): List<FileChange>

    suspend fun changedFilesUnstaged(): List<FileChange>

    suspend fun hunksOf(commit: CommitId, path: String, parentIndex: Int): DiffResult

    /** [FileComparison] 으로 표현한 임의 두 파일 스냅샷의 hunk 를 계산한다. */
    suspend fun hunksBetween(comparison: FileComparison): DiffResult
}
