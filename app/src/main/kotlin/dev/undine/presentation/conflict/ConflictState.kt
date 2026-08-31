package dev.undine.presentation.conflict

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.conflict.ConflictChoice
import dev.undine.domain.conflict.ConflictDocument
import dev.undine.domain.conflict.ConflictSegment
import dev.undine.domain.conflict.ConflictSide
import dev.undine.domain.conflict.ConflictedFile
import dev.undine.domain.merge.AbortConfirmation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 충돌 에디터 상태 홀더 — 파일 목록·선택 파일의 문서·진행률·저장 차단·중단 확인을 소유한다.
 *
 * UseCase 만 알고 Gateway 는 모른다. 실패는 빈 목록·성공으로 바꾸지 않고 [failure] 로 화면에 도달한다.
 *
 * @param repositoryState 지금 무엇이 진행 중인지. `continue` 가 병합인지 리베이스인지를 가르는 값이며
 *   배선이 주입한다 — 여기서 다시 읽으면 화면이 보는 상태와 어긋날 수 있다.
 */
@Stable
@Suppress("TooManyFunctions") // 파일 선택·구간 선택·직접 편집·저장·계속·중단 2단계가 한 화면의 전이다.
class ConflictState(
    private val actions: ConflictActions,
    private val repositoryState: () -> RepositoryState,
    private val scope: CoroutineScope,
) {
    var files: List<ConflictedFile> by mutableStateOf(emptyList())
        private set

    /** 지금 열어 둔 파일. `null` 이면 아직 고르지 않았다. */
    var selectedPath: String? by mutableStateOf(null)
        private set

    /** 선택 파일의 문서. 이진 파일이거나 아직 읽지 않았으면 `null` 이다. */
    var document: ConflictDocument? by mutableStateOf(null)
        private set

    /** 이번 세션에서 해결(저장)을 끝낸 경로. 파일별 해결 여부 표시의 근거다. */
    var resolvedPaths: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * 지금 들여다보는 충돌 구간. 세 패널이 이 구간의 ours·base·theirs 를 그린다.
     *
     * 화면 상태라 홀더가 소유한다 (compose-ui 규칙 1) — 컴포저블의 `remember` 에 두면 파일을
     * 바꿔도 이전 구간 번호가 남는다.
     */
    var focusedRegion: Int by mutableStateOf(0)
        private set

    /** 표식이 남아 저장을 막았을 때의 줄 번호. 비어 있으면 차단 상태가 아니다. */
    var blockedMarkerLines: List<Int> by mutableStateOf(emptyList())
        private set

    /** 확인을 기다리는 중단. 담긴 경로가 사용자에게 보여 준 "사라질 목록" 이다. */
    var abortConfirmation: AbortConfirmation? by mutableStateOf(null)
        private set

    /** 확인 뒤 편집이 늘어 중단이 거부됐다. 갱신된 목록으로 다시 확인받아야 한다. */
    var abortStale: Boolean by mutableStateOf(false)
        private set

    /** 중단이 거부된 사유. `MergeService` 가 준 문장을 그대로 보여준다. */
    var staleReason: String? by mutableStateOf(null)
        private set

    var failure: UndineException? by mutableStateOf(null)
        private set

    /**
     * 되돌리기 기록만 실패한 사유. null 이 아니면 **병합·리베이스는 이어졌고 Undo 항목만 남지 않았다.**
     *
     * 여기서는 값을 **전달만** 한다 — 문구를 그리는 일은 화면별 과제로 남겨 둔다 (결정 G30 3).
     */
    var undoRecordFailure: UndineException? by mutableStateOf(null)
        private set

    /** 충돌이 하나도 없는지 — 빈 상태 안내와 목록을 구분하는 기준이다. */
    val isClean: Boolean get() = files.isEmpty()

    val selectedFile: ConflictedFile? get() = files.firstOrNull { it.path == selectedPath }

    /** 이 파일에서 해결한 구간 수 / 전체 구간 수. 진행률 표시의 재료다. */
    val resolvedRegionCount: Int
        get() = document?.let { it.conflictCount - it.unresolvedCount } ?: 0

    val regionCount: Int get() = document?.conflictCount ?: 0

    /** 지금 구간. 문서가 없거나 충돌이 없으면 `null` 이다. */
    val focusedConflict: ConflictSegment.Conflict?
        get() = document?.segments
            ?.filterIsInstance<ConflictSegment.Conflict>()
            ?.getOrNull(focusedRegion)

    /** 저장할 수 있는지 — 문서를 읽었고 남은 구간이 없을 때만. */
    val canSave: Boolean get() = document?.isResolved == true

    fun refresh() {
        scope.launch { reload() }
    }

    /** 파일을 골라 내용을 읽는다. 이진 파일은 문서를 만들지 않는다 — 합칠 내용이 없다. */
    fun select(path: String) {
        selectedPath = path
        document = null
        focusedRegion = 0
        blockedMarkerLines = emptyList()
        val file = files.firstOrNull { it.path == path } ?: return
        if (file.isBinary) return
        scope.launch { loadDocument(path) }
    }

    /** 들여다볼 구간을 옮긴다. 범위를 벗어난 값은 무시한다. */
    fun focusRegion(index: Int) {
        if (index in 0 until regionCount) focusedRegion = index
    }

    /** 구간의 선택을 바꾼다. 문서는 불변이라 새 문서로 교체한다. */
    fun choose(regionIndex: Int, choice: ConflictChoice) {
        document = document?.choose(regionIndex, choice)
        // 선택이 바뀌면 이전 차단은 더 이상 그 내용이 아니다.
        blockedMarkerLines = emptyList()
    }

    /** 구간을 직접 편집한 내용으로 대체한다. */
    fun editRegion(regionIndex: Int, text: String) {
        choose(regionIndex, ConflictChoice.Edited(text.lines()))
    }

    /**
     * 해결 결과를 저장한다.
     *
     * **표식이 남아 있으면 저장하지 않는다.** 표식이 든 채로 스테이징되면 그대로 커밋되어 소스에
     * 표식이 박힌다 — 남은 위치를 [blockedMarkerLines] 로 알려 사용자가 찾아가게 한다.
     */
    fun save() {
        val path = selectedPath
        val current = document
        if (path == null || current == null) return
        val remaining = current.unresolvedLineNumbers()
        blockedMarkerLines = remaining
        if (remaining.isEmpty()) {
            runOperation {
                actions.resolve.execute(path, current.render())
                resolvedPaths = resolvedPaths + path
            }
        }
    }

    /** 이진 파일은 한쪽을 그대로 채택한다. */
    fun resolveBinary(side: ConflictSide) {
        val path = selectedPath ?: return
        runOperation {
            actions.resolve.executeBinary(path, side)
            resolvedPaths = resolvedPaths + path
        }
    }

    /** 남은 충돌이 없으면 상위 병합·리베이스를 이어간다. */
    fun continueOperation() {
        runOperation {
            undoRecordFailure = actions.continueAfterResolve.execute(repositoryState()).undoRecordFailure
        }
    }

    /**
     * 중단 확인을 요청한다. 이 호출로는 되돌리지 않는다 —
     * **지금 사라질 경로를 읽어** 확인에 담고, 사용자가 그 목록을 보고 동의해야 실행한다.
     */
    fun requestAbort() {
        abortStale = false
        staleReason = null
        scope.launch {
            try {
                abortConfirmation = AbortConfirmation.ofDiscardedPaths(discardedPaths())
            } catch (thrown: UndineException) {
                failure = thrown
            }
        }
    }

    /**
     * 사용자가 목록을 보고 동의했다.
     *
     * 확인 뒤 편집이 더 생겼으면 `MergeService` 가 거부한다 — 그때는 실패로 남기지 않고
     * [abortStale] 을 세워 **갱신된 목록으로 다시 확인**받는다. 사용자가 보지 않은 편집이
     * 사라지는 것을 막는 것이 그 거부의 목적이므로, 화면도 같은 이유로 다시 물어야 한다.
     */
    fun confirmAbort() {
        val confirmation = abortConfirmation ?: return
        scope.launch {
            try {
                actions.abort.execute(confirmation)
                abortConfirmation = null
                reload()
            } catch (staleConfirmation: UndineException.StateViolation) {
                // 거부 사유가 곧 화면 안내다 — 실패로 남기지 않고 갱신된 목록으로 다시 묻는다.
                staleReason = staleConfirmation.message
                abortConfirmation = AbortConfirmation.ofDiscardedPaths(discardedPaths())
                abortStale = true
            } catch (thrown: UndineException) {
                failure = thrown
            }
        }
    }

    fun dismiss() {
        abortConfirmation = null
        abortStale = false
        staleReason = null
        failure = null
        blockedMarkerLines = emptyList()
    }

    /**
     * 중단이 지우는 경로. 추적되지 않는 파일은 제외한다 — `reset --hard` 가 그 파일을 건드리지 않아
     * "사라진다" 고 확인받을 대상이 아니다 (`MergeService` 와 같은 기준).
     */
    private suspend fun discardedPaths(): List<String> {
        val status = actions.loadStatus.execute()
        return (status.staged.map { it.path } + status.unstaged.map { it.path } + status.conflicted)
            .distinct()
            .sorted()
    }

    private suspend fun loadDocument(path: String) {
        document = try {
            ConflictDocument.parse(actions.loadContent.execute(path))
        } catch (thrown: UndineException) {
            failure = thrown
            null
        }
    }

    /** 저장소를 바꾸는 조작. 성공하면 목록을 다시 읽고, 실패는 안내로 남긴다. */
    private fun runOperation(action: suspend () -> Unit) {
        failure = null
        blockedMarkerLines = emptyList()
        scope.launch {
            try {
                action()
            } catch (thrown: UndineException) {
                failure = thrown
                return@launch
            }
            reload()
        }
    }

    private suspend fun reload() {
        try {
            files = actions.loadFiles.execute()
        } catch (thrown: UndineException) {
            failure = thrown
            return
        }
        // 해결된 파일은 목록에서 빠진다 — 열어 둔 파일이 사라졌으면 선택을 비운다.
        if (selectedPath != null && files.none { it.path == selectedPath }) {
            selectedPath = null
            document = null
        }
    }
}
