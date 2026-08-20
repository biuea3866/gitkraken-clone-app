package dev.undine.domain

/** 변경 파일 목록과 hunk 계산. [changedFiles] 의 `parentIndex` 는 병합 커밋에서 비교할 부모를 고른다. */
interface DiffGateway {

    suspend fun changedFiles(commit: CommitId, parentIndex: Int): List<FileChange>

    suspend fun changedFilesStaged(): List<FileChange>

    suspend fun changedFilesUnstaged(): List<FileChange>

    suspend fun hunksOf(commit: CommitId, path: String, parentIndex: Int): DiffResult
}
