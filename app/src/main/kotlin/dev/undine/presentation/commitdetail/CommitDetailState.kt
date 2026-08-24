package dev.undine.presentation.commitdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.undine.application.commitdetail.LoadChangedFilesUseCase
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.FileChange
import dev.undine.domain.UndineException

/**
 * 비교 기준의 기본값 — 첫 부모다. 부모가 없는 최초 커밋도 이 값으로 읽으며,
 * 그때는 Gateway 가 빈 트리와 비교해 전체 파일을 추가로 돌려준다.
 */
const val FIRST_PARENT_INDEX: Int = 0

/**
 * 변경 파일 목록의 화면 상태.
 *
 * 실패를 빈 목록으로 접지 않고 [Failed] 로 따로 둔다 — 두 상태가 같은 모양이면
 * 사용자는 조회가 깨진 것을 변경 없음으로 오해한다.
 */
@Immutable
sealed interface ChangedFilesUiState {

    data object Loading : ChangedFilesUiState

    data class Loaded(val files: List<FileChange>) : ChangedFilesUiState

    data class Failed(val failure: UndineException) : ChangedFilesUiState
}

/**
 * 변경 파일 조회 한 건을 가리키는 열쇠. 커밋과 기준 부모가 **함께** 하나의 요청을 정한다 —
 * 둘 중 하나만 바뀌어도 앞선 결과는 다른 화면의 것이다.
 */
@Immutable
private data class ChangedFilesRequest(val commit: CommitId, val parentIndex: Int)

/** 어느 요청의 결과인지 함께 들고 있는 조회 결과. 열쇠와 상태가 따로 갱신되지 않도록 한 값으로 묶는다. */
@Immutable
private data class ChangedFilesResult(
    val request: ChangedFilesRequest,
    val uiState: ChangedFilesUiState,
)

/**
 * 커밋 상세 패널의 상태 홀더 — 기준 부모 선택, 본문 펼침 여부, 변경 파일 조회 결과를 들고 있다.
 *
 * **커밋 메타(작성자·커미터·부모·메시지)는 조회하지 않는다.** 그래프가 이미 읽어 둔
 * [Commit] 을 선택 상태로 받는다 (wave 3 결정 A4). 이 홀더가 아는 유일한 조회 경로는
 * [LoadChangedFilesUseCase] 이며 Gateway 를 직접 알지 못한다.
 *
 * 기준 부모와 펼침 여부는 **커밋별로** 기억한다 — 커밋을 오갈 때 앞 커밋에서 고른 두 번째 부모가
 * 다음 커밋에 따라붙으면 부모 수가 다른 커밋에서 범위를 벗어난다. 기억의 열쇠는 해시가 아니라
 * [Commit] 값 자체다: 패널이 받는 것이 커밋 값이고, 부모 구성이 다르면 다른 화면이기 때문이다.
 */
@Stable
class CommitDetailState(private val loadChangedFiles: LoadChangedFilesUseCase) {

    private val baseParentByCommit = mutableStateMapOf<Commit, Int>()
    private val messageExpandedByCommit = mutableStateMapOf<Commit, Boolean>()
    private var changedFilesResult: ChangedFilesResult? by mutableStateOf(null)

    /**
     * 주어진 커밋·기준 부모 조합의 조회 결과. 아직 그 조합의 결과가 아니면 [ChangedFilesUiState.Loading] 이다 —
     * 커밋이나 기준 부모를 바꾼 직후 앞 선택의 목록이나 실패 안내가 새 화면에 잠시 남지 않게 한다.
     */
    fun changedFilesOf(commit: CommitId, parentIndex: Int): ChangedFilesUiState =
        changedFilesResult
            ?.takeIf { it.request == ChangedFilesRequest(commit, parentIndex) }
            ?.uiState
            ?: ChangedFilesUiState.Loading

    /** 지금 비교 기준으로 삼은 부모. 고른 적이 없으면 첫 부모다. */
    fun baseParentIndexOf(commit: Commit): Int =
        baseParentByCommit[commit] ?: FIRST_PARENT_INDEX

    /**
     * 비교 기준 부모를 고른다. 부모가 없는 최초 커밋도 [FIRST_PARENT_INDEX] 하나는 받는다 —
     * 빈 트리와의 비교가 그 자리를 쓴다.
     *
     * 범위를 벗어난 값은 호출부 버그이므로 조용히 잘라내지 않고 막는다.
     */
    fun selectBaseParent(commit: Commit, parentIndex: Int) {
        val selectableParents = maxOf(commit.parents.size, 1)
        require(parentIndex in 0 until selectableParents) {
            "parentIndex $parentIndex is outside 0..${selectableParents - 1} for commit ${commit.id}"
        }
        baseParentByCommit[commit] = parentIndex
    }

    fun isMessageExpanded(commit: Commit): Boolean = messageExpandedByCommit[commit] == true

    fun toggleMessage(commit: Commit) {
        messageExpandedByCommit[commit] = !isMessageExpanded(commit)
    }

    /**
     * 변경 파일 목록을 읽어 상태에 담는다. 도메인 실패는 [ChangedFilesUiState.Failed] 로 노출한다 —
     * 취소는 [UndineException] 이 아니므로 이 catch 를 지나 그대로 전파된다.
     */
    suspend fun load(commit: CommitId, parentIndex: Int) {
        // 읽는 동안은 앞 요청의 결과를 버린다 — 열쇠가 같은 재조회에서도 낡은 목록을 보여주지 않는다.
        changedFilesResult = null
        val uiState = try {
            ChangedFilesUiState.Loaded(loadChangedFiles.execute(commit, parentIndex))
        } catch (failure: UndineException) {
            ChangedFilesUiState.Failed(failure)
        }
        changedFilesResult = ChangedFilesResult(ChangedFilesRequest(commit, parentIndex), uiState)
    }
}

/** 컴포지션 수명 동안 유지되는 상세 패널 상태. UseCase 가 바뀌면 상태도 새로 만든다. */
@Composable
fun rememberCommitDetailState(loadChangedFiles: LoadChangedFilesUseCase): CommitDetailState =
    remember(loadChangedFiles) { CommitDetailState(loadChangedFiles) }
