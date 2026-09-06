package dev.undine.presentation.patch

import dev.undine.domain.Commit
import dev.undine.domain.DiffResult
import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.PatchExport
import java.io.IOException
import java.nio.file.Path

/** 무엇을 대상으로 패치를 만들 것인가. */
enum class PatchSource { WORKING_TREE, COMMITS }

/** 생성 결과를 어떤 형태로 저장할 것인가. 저장 대상(파일/디렉터리)이 여기서 갈린다. */
enum class PatchFormat { COMBINED, PER_COMMIT }

/**
 * 적용 범위 선택지. `ApplyMode` 를 그대로 쓰지 않는 이유는 커밋 모드의 작성자·메시지가 **선택 시점에는
 * 아직 없기** 때문이다 — 화면은 무엇을 고를지만 담고, 실제 [dev.undine.domain.patch.ApplyMode] 는
 * 적용 직전에 입력값과 합쳐 만든다.
 */
enum class PatchApplyModeChoice { WORKING_TREE_ONLY, INDEX, CREATE_COMMIT }

/** 최근 커밋 한 페이지의 조회 상태. 빈 목록은 실패가 아니라 실제 조회 결과다. */
sealed interface PatchCommitsUiState {
    data object Idle : PatchCommitsUiState
    data object Loading : PatchCommitsUiState
    data class Loaded(val commits: List<Commit>) : PatchCommitsUiState
    data class Failed(val failure: UndineException) : PatchCommitsUiState
}

/** 저장하기 전에 보여 주는 생성 결과 — 포함 파일과 총 크기. */
data class PatchPreparation(
    val export: PatchExport,
    val paths: List<String>,
    val totalBytes: Int,
)

/** 생성 진행 상태. 진행 중을 별도 변이로 두어 긴 작업이 무반응으로 보이지 않게 한다. */
sealed interface PatchPreparationUiState {
    data object Idle : PatchPreparationUiState
    data object Preparing : PatchPreparationUiState
    data class Ready(val preparation: PatchPreparation) : PatchPreparationUiState
    data class Failed(val failure: UndineException) : PatchPreparationUiState
}

/**
 * 저장 결과. 덮어쓰기 확인은 저장 **전에** 끼어드는 별도 변이다 — 조용히 덮어쓰지 않는다.
 *
 * [Saving] 을 따로 두는 이유는 커밋당 파일 저장이 파일 여러 개를 디스크에 쓰기 때문이다 — 진행 중을
 * 표시하지 않으면 큰 패치에서 화면이 멈춘 것처럼 보인다.
 */
sealed interface PatchSaveUiState {
    data object Idle : PatchSaveUiState
    data object Saving : PatchSaveUiState
    data class ConfirmOverwrite(val target: PatchSaveTarget, val existingNames: List<String>) : PatchSaveUiState
    data class Saved(val target: String) : PatchSaveUiState

    /**
     * [rollbackFailed] 는 실패 뒤 되돌리기까지 실패했다는 뜻이다 — 폴더에 새 파일이 남거나 덮어쓴
     * 파일이 복구되지 않았을 수 있다. 이 사실을 로그로만 남기면 사용자는 자기 폴더 상태를 모른다.
     *
     * [leftoverTemporaries] 는 지우지 못한 임시 파일의 경로다. 같은 이유로 화면까지 올린다 —
     * 반쯤 쓰인 파일이 사용자가 고른 폴더에 남았고, 치울 수 있는 것은 사용자뿐이다.
     */
    data class Failed(
        val reason: String,
        val rollbackFailed: Boolean = false,
        val leftoverTemporaries: List<String> = emptyList(),
    ) : PatchSaveUiState
}

/** 어디에 무엇을 쓸 것인가. 덮어쓰기 확인을 거쳐도 같은 값으로 그대로 쓴다. */
data class PatchSaveTarget(val entries: List<PatchFileWrite>, val label: String)

data class PatchFileWrite(val path: Path, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PatchFileWrite && path == other.path && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
}

/**
 * 고른 패치를 읽는 진행 상태.
 *
 * 네 갈래를 한 타입에 담는다 — 아직 고르지 않음([Idle])·읽는 중([Reading])·읽음([Loaded])·못 읽음
 * ([Failed]). 진행 중을 별도 변이로 두지 않으면 큰 패치를 읽고 파싱하는 동안 화면이 아무 반응 없는
 * 것처럼 보인다. 못 읽은 것을 [Idle] 로 접으면 "고르지 않았다" 와 구분되지 않는다.
 */
sealed interface PatchReadUiState {
    data object Idle : PatchReadUiState
    data object Reading : PatchReadUiState
    data class Loaded(val patch: SelectedPatch) : PatchReadUiState
    data class Failed(val failure: IOException) : PatchReadUiState
}

/** 고른 패치 하나 — 바이트와, 그 바이트에서 읽은 미리보기·커밋 메타데이터. */
data class SelectedPatch(
    val path: Path,
    val bytes: ByteArray,
    val preview: DiffResult,
    val metadata: dev.undine.application.patch.PatchCommitMetadata,
) {
    val name: String get() = path.fileName?.toString().orEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || (other is SelectedPatch && path == other.path && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
}

/**
 * dry-run 과 apply 의 결과 상태.
 *
 * **둘이 같은 타입을 쓴다** — 게이트웨이가 같은 [ApplyOutcome] 을 주므로 화면도 같은 매핑으로 다룬다.
 * "검사는 통과했는데 적용은 실패" 를 화면이 새로 만들어내지 않는다.
 *
 * [Refused] 는 `UndineException.StateViolation` 이다 — 경로 이탈·복원 불가처럼 게이트웨이가
 * 결과가 아니라 거부로 답한 경우이며, 조회 실패([Failed])와 구분해야 사용자가 사유를 읽을 수 있다.
 */
sealed interface PatchOutcomeUiState {
    data object Idle : PatchOutcomeUiState
    data object Running : PatchOutcomeUiState
    data class Completed(val outcome: ApplyOutcome) : PatchOutcomeUiState
    data class Refused(val violation: UndineException.StateViolation) : PatchOutcomeUiState
    data class Failed(val failure: UndineException) : PatchOutcomeUiState
}
