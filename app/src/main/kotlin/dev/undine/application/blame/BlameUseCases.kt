package dev.undine.application.blame

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.DiffResult
import dev.undine.domain.FileComparison
import dev.undine.domain.UndineException
import dev.undine.domain.blame.BlameGateway
import dev.undine.domain.blame.BlameResult
import dev.undine.domain.blame.FileHistoryEntry
import dev.undine.domain.blame.LineRange

private const val DEFAULT_HISTORY_LIMIT = 100

/**
 * 파일의 라인별 최종 수정 커밋을 읽는다.
 *
 * 범위를 받는 이유는 blame 이 비싸기 때문이다 — 화면은 보이는 구간만 요청하고 스크롤에 따라 넓힌다.
 *
 * @throws UndineException.NotFound 그 커밋에 그 경로가 없을 때
 */
class LoadBlameUseCase(private val blameGateway: BlameGateway) {

    suspend fun execute(
        path: String,
        range: LineRange,
        ignoreWhitespace: Boolean = false,
        at: CommitId? = null,
    ): BlameResult = blameGateway.blame(path, range, ignoreWhitespace, at)
}

/**
 * 파일을 건드린 커밋을 최신부터 읽는다. 이름 변경을 따라가므로 rename 이전 이력도 이어진다.
 *
 * @param at 기준 커밋. null 이면 HEAD. **삭제된 파일**은 그 파일이 있던 커밋을 지정한다.
 * @throws UndineException.NotFound 그 커밋에서 그 경로를 찾을 수 없을 때
 */
class LoadFileHistoryUseCase(private val blameGateway: BlameGateway) {

    suspend fun execute(
        path: String,
        at: CommitId? = null,
        limit: Int = DEFAULT_HISTORY_LIMIT,
    ): List<FileHistoryEntry> = blameGateway.fileHistory(path, at, limit)
}

/**
 * 파일 이력에서 고른 두 시점을 비교한다. 부모 인덱스는 두 시점의 관계를 표현하지 못하므로 받지 않는다.
 */
class CompareFileHistoryUseCase(private val diffGateway: DiffGateway) {

    suspend fun execute(comparison: FileComparison): DiffResult = diffGateway.hunksBetween(comparison)
}
