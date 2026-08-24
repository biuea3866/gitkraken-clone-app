package dev.undine.presentation.staging

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.staging.AmendOutcome
import dev.undine.domain.CommitId
import dev.undine.domain.DiffHunk
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.presentation.i18n.CommitBlockedReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 제목 길이 가이드. 강제하지 않고 표시만 한다. */
const val SUBJECT_LENGTH_GUIDE: Int = 50

/** 본문 줄바꿈 가이드. */
const val BODY_WRAP_GUIDE: Int = 72

/**
 * 스테이징 패널 상태 홀더 — 두 목록·선택·메시지·amend 확인을 소유한다.
 *
 * **스테이징 상태의 단일 소유자다.** Diff 뷰어(UND-16)가 올리는 hunk 스테이징도 여기로 들어온다 —
 * 두 곳에서 인덱스를 만지면 목록이 서로 다른 시점을 보여준다.
 *
 * UseCase 만 알고 Gateway 는 모른다. 실패는 빈 목록·성공으로 바꾸지 않고 [failure] 로 화면에 도달한다.
 * `UndineException` 만 잡으므로 취소는 그대로 전파된다.
 *
 * @param scope 화면 수명에 묶인 스코프. 홀더가 스코프를 만들지 않아 화면이 사라지면 작업도 취소된다.
 */
@Stable
@Suppress("TooManyFunctions") // 두 목록 조작·선택·메시지·amend 2단계가 한 화면의 상태 전이다.
class StagingState(
    private val actions: StagingActions,
    private val scope: CoroutineScope,
) {
    var status: WorkingTreeStatus? by mutableStateOf(null)
        private set

    /** 사용자가 고른 경로. 두 목록에 걸쳐 고를 수 없다 — 올릴지 내릴지가 갈린다. */
    var selection: StagingSelection by mutableStateOf(StagingSelection.EMPTY)
        private set

    var message: String by mutableStateOf("")
        private set

    /** amend 로 커밋할지. 켜면 커밋 버튼이 amend 경로를 탄다. */
    var amendRequested: Boolean by mutableStateOf(false)
        private set

    /** 확인을 기다리는 amend 대상. `null` 이면 대기 중인 것이 없다. */
    var amendConfirmation: CommitId? by mutableStateOf(null)
        private set

    /** 작성자 미설정이 확인된 상태. 커밋을 한 번 시도해야 알 수 있다. */
    var authorMissing: Boolean by mutableStateOf(false)
        private set

    var failure: UndineException? by mutableStateOf(null)
        private set

    /** 진행 중인 커밋. 연속 입력이 커밋을 두 번 만들지 않게 하는 근거다. */
    var committing: Boolean by mutableStateOf(false)
        private set

    val staged: List<String> get() = status?.staged?.map { it.path }.orEmpty()

    val unstaged: List<String>
        get() = status?.let { current ->
            (current.unstaged.map { it.path } + current.untracked).distinct().sorted()
        }.orEmpty()

    val conflicted: List<String> get() = status?.conflicted.orEmpty()

    /** 변경이 하나도 없는지 — 빈 상태 안내와 목록을 구분하는 기준이다. */
    val isClean: Boolean
        get() = status?.let { staged.isEmpty() && unstaged.isEmpty() && conflicted.isEmpty() } == true

    /**
     * 커밋을 막는 사유. `null` 이면 커밋할 수 있다.
     *
     * 순서가 있다 — 스테이징이 비었으면 메시지를 아무리 써도 커밋할 수 없으므로 그것을 먼저 알린다.
     */
    val blockedReason: CommitBlockedReason?
        get() = when {
            staged.isEmpty() && !amendRequested -> CommitBlockedReason.NOTHING_STAGED
            message.isBlank() -> CommitBlockedReason.EMPTY_MESSAGE
            authorMissing -> CommitBlockedReason.AUTHOR_MISSING
            else -> null
        }

    fun refresh() {
        scope.launch { reload() }
    }

    fun changeMessage(value: String) {
        message = value
    }

    fun requestAmendMode(requested: Boolean) {
        amendRequested = requested
        // 대기 중인 확인은 그 요청에 딸린 것이다 — 체크를 끄면 확인도 사라진다.
        if (!requested) amendConfirmation = null
    }

    /** 목록에서 고른다. 다른 목록을 고르면 이전 선택을 비운다. */
    fun select(side: StagingSide, paths: Set<String>) {
        selection = StagingSelection(side = side, paths = paths)
    }

    fun stageSelected() {
        val paths = selection.pathsOn(StagingSide.UNSTAGED).ifEmpty { unstaged.toSet() }
        runOperation { actions.stageFiles.execute(paths.toList()) }
    }

    fun unstageSelected() {
        val paths = selection.pathsOn(StagingSide.STAGED).ifEmpty { staged.toSet() }
        runOperation { actions.unstageFiles.execute(paths.toList()) }
    }

    /** Diff 뷰어가 올리는 hunk 스테이징. 패널이 받아 UseCase 로 넘긴다. */
    fun stageHunk(path: String, hunks: List<DiffHunk>) {
        runOperation { actions.stageHunks.execute(path, hunks) }
    }

    /**
     * 커밋한다. amend 가 요청됐으면 [AmendCommitUseCase] 를 타고, 대상이 원격에 있으면
     * **실행하지 않고** [amendConfirmation] 을 세운다 — 확인 절차 없이 HEAD 를 다시 쓰지 않는다.
     */
    fun commit() {
        if (blockedReason != null || committing) return
        committing = true
        scope.launch {
            try {
                if (amendRequested) requestAmend() else commitNew()
            } catch (thrown: UndineException) {
                handle(thrown)
            } finally {
                committing = false
            }
        }
    }

    /** 사용자가 대상 커밋을 보고 동의했다. [amendConfirmation] 의 대상 그대로 실행한다. */
    fun confirmAmend() {
        val target = amendConfirmation ?: return
        if (committing) return
        committing = true
        scope.launch {
            try {
                actions.amendCommit.confirm(message, target)
                finishCommit()
            } catch (thrown: UndineException) {
                handle(thrown)
            } finally {
                committing = false
            }
        }
    }

    /** 확인을 취소한다. 저장소는 바뀌지 않는다. */
    fun dismiss() {
        amendConfirmation = null
        failure = null
    }

    private suspend fun commitNew() {
        actions.commitStaged.execute(message)
        finishCommit()
    }

    private suspend fun requestAmend() {
        when (val outcome = actions.amendCommit.request(message)) {
            is AmendOutcome.Amended -> finishCommit()
            is AmendOutcome.ConfirmationRequired -> amendConfirmation = outcome.target
        }
    }

    /** 커밋이 끝났다. 메시지를 비우고 목록을 다시 읽는다 — 성공한 메시지가 남으면 두 번 커밋하게 된다. */
    private suspend fun finishCommit() {
        message = ""
        amendRequested = false
        amendConfirmation = null
        authorMissing = false
        selection = StagingSelection.EMPTY
        reload()
    }

    private fun handle(thrown: UndineException) {
        // 작성자 미설정은 실패 안내가 아니라 **커밋 버튼의 사유**로 보여야 한다 — 설정하면 풀린다.
        if (thrown is UndineException.AuthorNotConfigured) {
            authorMissing = true
            return
        }
        failure = thrown
    }

    /** 목록을 바꾸는 조작. 성공하면 목록을 다시 읽고, 실패는 안내로 남긴다. */
    private fun runOperation(action: suspend () -> Unit) {
        failure = null
        scope.launch {
            try {
                action()
            } catch (thrown: UndineException) {
                failure = thrown
                return@launch
            }
            selection = StagingSelection.EMPTY
            reload()
        }
    }

    private suspend fun reload() {
        status = try {
            actions.loadStatus.execute()
        } catch (thrown: UndineException) {
            failure = thrown
            null
        }
    }
}

/** 목록 구분. 선택은 한쪽에만 걸린다. */
enum class StagingSide {
    STAGED,
    UNSTAGED,
}

/**
 * 고른 경로와 그 경로가 속한 목록.
 *
 * 목록을 함께 담는 이유는 같은 경로가 두 목록에 동시에 있을 수 있기 때문이다 —
 * 일부만 stage 한 파일은 staged 와 unstaged 양쪽에 나타난다.
 */
data class StagingSelection(val side: StagingSide?, val paths: Set<String>) {

    fun pathsOn(target: StagingSide): Set<String> = if (side == target) paths else emptySet()

    companion object {
        val EMPTY = StagingSelection(side = null, paths = emptySet())
    }
}
