package dev.undine.presentation.blame

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.undine.application.blame.CompareFileHistoryUseCase
import dev.undine.application.blame.LoadBlameUseCase
import dev.undine.application.blame.LoadFileHistoryUseCase
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.DiffResult
import dev.undine.domain.FileComparison
import dev.undine.domain.UndineException
import dev.undine.domain.blame.BlameLine
import dev.undine.domain.blame.BlameResult
import dev.undine.domain.blame.FileHistoryEntry
import dev.undine.domain.blame.LineRange

@Immutable
sealed interface BlameUiState {
    data object Idle : BlameUiState
    data object Loading : BlameUiState
    data class Loaded(val lines: List<BlameLine>) : BlameUiState
    data object Unsupported : BlameUiState
    data class Failed(val failure: UndineException) : BlameUiState
}

@Immutable
sealed interface FileHistoryUiState {
    data object Idle : FileHistoryUiState
    data object Loading : FileHistoryUiState
    data class Loaded(val entries: List<FileHistoryEntry>) : FileHistoryUiState
    data class Failed(val failure: UndineException) : FileHistoryUiState
}

@Immutable
sealed interface DiffUiState {
    data object Idle : DiffUiState
    data object Loading : DiffUiState
    data class Loaded(val result: DiffResult) : DiffUiState
    data class Failed(val failure: UndineException) : DiffUiState
}

/** 화면 행. gutter 는 같은 커밋이 연속할 때 첫 행에만 그린다. */
@Immutable
data class BlameRow(val blameLine: BlameLine, val showCommitGutter: Boolean) {
    val line: Int get() = blameLine.line
}

internal fun blameRowsOf(lines: List<BlameLine>): List<BlameRow> {
    var previousCommit: CommitId? = null
    return lines.map { line ->
        BlameRow(blameLine = line, showCommitGutter = line.commit.id != previousCommit).also {
            previousCommit = line.commit.id
        }
    }
}

@Immutable
private data class BlameRequest(
    val path: String,
    val range: LineRange,
    val ignoreWhitespace: Boolean,
    val at: CommitId?,
) {
    /**
     * 이 요청이 기대하는 줄 수. 응답이 이보다 적으면 파일 끝에 닿은 것이다.
     * 전체 범위는 상한이 `Int.MAX_VALUE` 라 곱셈 없이 Long 으로 센다.
     */
    val requestedLineCount: Long get() = range.end.toLong() - range.start + 1
}

/**
 * Blame·파일 이력 화면의 비즈니스 상태. Gateway 를 직접 알지 않고 UseCase 로만 조회하며,
 * Composable 밖에 있으므로 스크롤 확장·공백 무시·재귀가 재구성에 흔들리지 않는다.
 */
@Stable
class BlameHistoryState(
    private val loadBlame: LoadBlameUseCase,
    private val loadHistory: LoadFileHistoryUseCase,
    private val compareHistory: CompareFileHistoryUseCase,
) {
    var blame: BlameUiState by mutableStateOf(BlameUiState.Idle)
        private set
    var history: FileHistoryUiState by mutableStateOf(FileHistoryUiState.Idle)
        private set
    var comparison: DiffUiState by mutableStateOf(DiffUiState.Idle)
        private set
    var pendingComparisonEntry: FileHistoryEntry? by mutableStateOf(null)
        private set
    var ignoreWhitespace: Boolean by mutableStateOf(false)
        private set

    private var currentRequest: BlameRequest? = null
    private var cachedLines: Map<Int, BlameLine> = emptyMap()
    private var blameLoading = false
    private var historyLoading = false
    private var comparisonLoading = false

    /** 요청한 만큼 못 받으면 파일 끝이다 — 그 뒤로는 스크롤이 아무리 닿아도 다시 부르지 않는다. */
    private var reachedEnd = false

    suspend fun loadBlame(
        path: String,
        range: LineRange,
        ignoreWhitespace: Boolean = this.ignoreWhitespace,
        at: CommitId? = null,
    ) {
        this.ignoreWhitespace = ignoreWhitespace
        cachedLines = emptyMap()
        load(BlameRequest(path, range, ignoreWhitespace, at), append = false)
    }

    /**
     * 읽어 둔 구간 다음 [EXPANSION_LINES] 줄을 이어 읽는다. 같은 줄은 최신 응답으로 덮어 중복 행이
     * 생기지 않게 한다.
     *
     * 스크롤이 하단에 닿을 때마다 불리므로 **진행 중 요청과 파일 끝 도달을 여기서 막는다** — 막지
     * 않으면 같은 구간을 스크롤 프레임마다 다시 요청한다.
     */
    suspend fun loadNextLineRange() {
        if (blameLoading || reachedEnd) return
        val request = currentRequest
        val loadedEnd = cachedLines.keys.maxOrNull()
        if (request != null && loadedEnd != null) {
            load(request.copy(range = LineRange.of(loadedEnd + 1, loadedEnd + EXPANSION_LINES)), append = true)
        }
    }

    /** 공백 무시는 마지막 요청의 같은 기준·범위를 즉시 다시 읽는다. */
    suspend fun setIgnoreWhitespace(enabled: Boolean) {
        if (ignoreWhitespace == enabled) return
        ignoreWhitespace = enabled
        currentRequest?.let { request ->
            cachedLines = emptyMap()
            load(request.copy(ignoreWhitespace = enabled), append = false)
        }
    }

    /** 선택 커밋 자체를 빼기 위해 첫 부모를 새 blame 기준으로 쓴다. */
    suspend fun recurseBefore(commit: Commit) {
        val parent = commit.parents.firstOrNull()
        val request = currentRequest
        if (parent == null) {
            blame = BlameUiState.Failed(UndineException.StateViolation("최초 커밋 이전으로는 blame 할 수 없습니다"))
            return
        }
        if (request != null) {
            cachedLines = emptyMap()
            load(request.copy(at = parent), append = false)
        }
    }

    suspend fun loadHistory(path: String, at: CommitId? = null, limit: Int = DEFAULT_HISTORY_LIMIT) {
        if (historyLoading) return
        historyLoading = true
        history = FileHistoryUiState.Loading
        try {
            history = FileHistoryUiState.Loaded(loadHistory.execute(path = path, at = at, limit = limit))
        } catch (failure: UndineException) {
            history = FileHistoryUiState.Failed(failure)
        } finally {
            historyLoading = false
        }
    }

    suspend fun compareHistoryEntries(before: FileHistoryEntry, after: FileHistoryEntry) {
        if (comparisonLoading) return
        comparisonLoading = true
        comparison = DiffUiState.Loading
        try {
            comparison = DiffUiState.Loaded(
                compareHistory.execute(
                    FileComparison(
                        before = before.commit.id,
                        beforePath = before.path,
                        after = after.commit.id,
                        afterPath = after.path,
                    ),
                ),
            )
        } catch (failure: UndineException) {
            comparison = DiffUiState.Failed(failure)
        } finally {
            comparisonLoading = false
        }
    }

    /** 이력 행을 두 번 고르면 첫 시점과 두 번째 시점의 파일 경로를 보존한 diff를 요청한다. */
    suspend fun selectHistoryEntry(entry: FileHistoryEntry) {
        val before = pendingComparisonEntry
        if (before == null || before == entry) {
            pendingComparisonEntry = if (before == entry) null else entry
            return
        }
        pendingComparisonEntry = null
        compareHistoryEntries(before, entry)
    }

    private suspend fun load(request: BlameRequest, append: Boolean) {
        if (blameLoading) return
        blameLoading = true
        // 이어 읽을 때는 이미 보여 준 줄을 지우지 않는다 — 목록이 사라지면 스크롤 위치도 함께 잃는다.
        if (!append) {
            reachedEnd = false
            blame = BlameUiState.Loading
        }
        try {
            when (val result = loadBlame.execute(request.path, request.range, request.ignoreWhitespace, request.at)) {
                BlameResult.Unsupported -> blame = BlameUiState.Unsupported
                is BlameResult.Lines -> {
                    cachedLines = if (append) {
                        cachedLines + result.lines.associateBy { it.line }
                    } else {
                        result.lines.associateBy { it.line }
                    }
                    currentRequest = request
                    reachedEnd = result.lines.size < request.requestedLineCount
                    blame = BlameUiState.Loaded(cachedLines.toSortedMap().values.toList())
                }
            }
        } catch (failure: UndineException) {
            blame = BlameUiState.Failed(failure)
        } finally {
            blameLoading = false
        }
    }

    private companion object {
        const val DEFAULT_HISTORY_LIMIT = 100

        /** 스크롤 확장 한 번에 이어 읽는 줄 수. blame 은 비싸므로 화면 몇 개 분량만 앞서 읽는다. */
        const val EXPANSION_LINES = 200
    }
}

@Composable
fun rememberBlameHistoryState(
    loadBlame: LoadBlameUseCase,
    loadHistory: LoadFileHistoryUseCase,
    compareHistory: CompareFileHistoryUseCase,
): BlameHistoryState = remember(loadBlame, loadHistory, compareHistory) {
    BlameHistoryState(loadBlame, loadHistory, compareHistory)
}
