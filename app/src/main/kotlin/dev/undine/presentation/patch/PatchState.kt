package dev.undine.presentation.patch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.patch.PATCH_COMMIT_PAGE_SIZE
import dev.undine.application.patch.PatchActions
import dev.undine.application.patch.commitRangeOf
import dev.undine.application.patch.patchPathsOf
import dev.undine.domain.Commit
import dev.undine.domain.Person
import dev.undine.domain.RepositorySessionKey
import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.CommitRange
import dev.undine.domain.patch.PatchExport
import dev.undine.domain.patch.WorkingTreeScope
import dev.undine.presentation.diff.DiffViewMode
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Patch 화면의 상태 홀더.
 *
 * 생성과 적용을 한 홀더가 갖는 이유는 두 흐름이 **같은 화면의 두 절반**이고, 적용에 쓰는 패치를 방금
 * 만든 것에서 이어 받는 경로가 자연스럽기 때문이다. 홀더는 [PatchActions] 만 알고 Gateway·JGit 을
 * 알지 못한다 (레이어 규칙 3).
 *
 * 파일 대화상자와 읽기·쓰기는 [PatchFiles] 경계 뒤에 있다 — 화면 테스트가 실제 대화상자를 열거나
 * 디스크에 쓰지 않게 한다.
 *
 * **비동기 작업은 전부 [launchJob] 하나를 거친다.** 갈래마다 세대·수명·진행 상태를 [PatchJobLane] 이
 * 함께 갖고, 지점마다 플래그를 따로 두지 않는다 — 따로 두면 새 비동기 경로를 더할 때마다 같은 계열의
 * 결함(늦게 끝난 결과가 바뀐 선택을 덮음·같은 작업이 겹쳐 돎·도는 중에 진행이 지워짐)이 재발한다.
 *
 * **디스크 I/O 와 파싱은 [ioDispatcher] 로 옮긴다.** [scope] 는 컴포지션의 스코프라 그대로 두면
 * UI 스레드에서 돌아, 큰 패치를 읽거나 여러 파일을 저장하는 동안 화면이 멈춘다. Git 호출은
 * [PatchActions] 구현이 이미 자기 디스패처를 갖고 있어 다시 옮기지 않는다.
 *
 * **실패를 빈 결과로 접지 않는다.** 조회 실패·적용 거부·저장 실패는 각각 자기 변이로 남아 화면에
 * 도달한다. 취소는 잡지 않고 그대로 전파한다 (exception-handling 규칙 5).
 */
@Stable
@Suppress("TooManyFunctions") // 생성·적용 두 흐름의 독립된 화면 전이를 한 상태 홀더가 소유한다.
class PatchState(
    private val actions: PatchActions,
    private val files: PatchFiles,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var source: PatchSource by mutableStateOf(PatchSource.WORKING_TREE)
        private set

    /**
     * 생성 범위. 기본값이 [WorkingTreeScope.ALL] 인 이유는 "현재 변경을 대상으로" 에 가장 가깝고,
     * 생성은 저장소를 바꾸지 않아 적용 모드처럼 가장 안전한 값을 기본으로 둘 이유가 없어서다.
     */
    var workingTreeScope: WorkingTreeScope by mutableStateOf(WorkingTreeScope.ALL)
        private set

    var format: PatchFormat by mutableStateOf(PatchFormat.COMBINED)
        private set

    /** 최근 커밋 한 페이지 조회. 다시 부르면 마지막 조회만 남는다. */
    private val commitsJob = PatchJobLane<PatchCommitsUiState>(PatchCommitsUiState.Idle, PatchJobMode.LATEST_WINS)

    /** 생성 준비. 대상·범위·커밋 선택이 바뀌면 [PatchJobLane.reset] 이 도는 작업의 결과까지 버린다. */
    private val createJob =
        PatchJobLane<PatchPreparationUiState>(PatchPreparationUiState.Idle, PatchJobMode.LATEST_WINS)

    /**
     * 저장. 겹쳐 돌면 한쪽 실패의 되돌리기가 다른 쪽이 방금 만든 파일을 지운다
     * ([PatchFiles.writeAllOrRollback] 은 자기 목록만 알고 옆 저장이 만든 파일을 구분하지 못한다).
     */
    private val saveJob = PatchJobLane<PatchSaveUiState>(PatchSaveUiState.Idle, PatchJobMode.EXCLUSIVE)

    /** 고른 패치 읽기·파싱. 다른 패치를 고르면 앞선 읽기의 결과는 버려진다. */
    private val readJob = PatchJobLane<PatchReadUiState>(PatchReadUiState.Idle, PatchJobMode.LATEST_WINS)

    private val dryRunJob = PatchJobLane<PatchOutcomeUiState>(PatchOutcomeUiState.Idle, PatchJobMode.LATEST_WINS)

    /**
     * 적용. 저장소를 바꾸므로 겹쳐 돌게 두지 않고, 시작한 적용의 결과는 그 사이 다른 패치를 골랐더라도
     * 반드시 화면에 앉힌다 — 바꿔 놓고 아무 일도 없던 것처럼 보이는 쪽이 훨씬 나쁘다.
     */
    private val applyJob = PatchJobLane<PatchOutcomeUiState>(PatchOutcomeUiState.Idle, PatchJobMode.EXCLUSIVE)

    val commits: PatchCommitsUiState
        get() = commitsJob.state

    /** 목록에서 고른 **연속 구간**의 인덱스 범위. 비어 있으면 생성 버튼이 막힌다. */
    var selectedCommitRange: IntRange? by mutableStateOf(null)
        private set

    val preparation: PatchPreparationUiState
        get() = createJob.state

    val save: PatchSaveUiState
        get() = saveJob.state

    /** 고른 패치를 읽는 진행 상태. 읽는 중·읽음·못 읽음이 한 타입에 닫혀 있다. */
    val patchRead: PatchReadUiState
        get() = readJob.state

    val selectedPatch: SelectedPatch?
        get() = (patchRead as? PatchReadUiState.Loaded)?.patch

    /** 드롭을 받지 않은 사유. `null` 이면 거부한 적이 없다 — 실패가 아니라 안내다. */
    var dropRejection: PatchDrop.Reason? by mutableStateOf(null)
        private set

    /** 패치 파일을 읽지 못한 사유. 취소(고르지 않음)와 구분한다. */
    val readFailure: IOException?
        get() = (patchRead as? PatchReadUiState.Failed)?.failure

    val dryRun: PatchOutcomeUiState
        get() = dryRunJob.state

    val applyResult: PatchOutcomeUiState
        get() = applyJob.state

    var applyMode: PatchApplyModeChoice by mutableStateOf(PatchApplyModeChoice.WORKING_TREE_ONLY)
        private set

    var commitAuthorName: String by mutableStateOf("")
        private set

    var commitAuthorEmail: String by mutableStateOf("")
        private set

    var commitMessage: String by mutableStateOf("")
        private set

    var previewViewMode: DiffViewMode by mutableStateOf(DiffViewMode.UNIFIED)
        private set

    /** 목록에서 고른 커밋들. 목록 순서(최신 → 과거)를 그대로 유지한다. */
    val selectedCommits: List<Commit>
        get() {
            val loaded = (commits as? PatchCommitsUiState.Loaded)?.commits ?: return emptyList()
            val range = selectedCommitRange ?: return emptyList()
            return loaded.slice(range.first..minOf(range.last, loaded.lastIndex))
        }

    /** 생성을 시작할 수 있는가. 커밋 모드에서 선택이 비면 막는다. */
    val canPrepare: Boolean
        get() = exportRequest() != null

    /** 패치가 커밋 작성자·메시지를 이미 담고 있는가. 담고 있으면 입력을 요구하지 않는다. */
    val hasPatchCommitMetadata: Boolean
        get() = selectedPatch?.metadata?.isEmpty == false

    /**
     * 적용 버튼을 누를 수 있는가.
     *
     * dry-run 이 [ApplyOutcome.Applied] 였을 때만 연다. 충돌·미지원·거부는 물론이고 **변경 없음**도
     * 누를 이유가 없다 — 게이트웨이가 거부할 것을 화면이 먼저 막아, 사용자가 실패를 눌러 보고 알게
     * 하지 않는다.
     *
     * 앞선 적용이 도는 동안에도 막는다 ([PatchJobLane.isRunning]) — 다른 패치를 고른 뒤 그 검사가
     * 통과하면 두 적용이 잇달아 저장소에 얹히기 때문이다.
     */
    val canApply: Boolean
        get() = selectedPatch != null &&
            (dryRun as? PatchOutcomeUiState.Completed)?.outcome is ApplyOutcome.Applied &&
            !applyJob.isRunning &&
            commitInputSatisfied

    /**
     * 저장소나 디스크를 **이미 바꾸고 있는** 작업이 도는 중인가.
     *
     * 배선이 이 값으로 PATCH 이탈을 막는다 (결정 C3). [scope] 는 컴포지션의 스코프라 화면을 떠나면
     * 취소되는데, 그러면 적용·저장이 사용자 모르게 끊기고 무엇이 남았는지 알릴 자리까지 함께 사라진다.
     *
     * 값은 갈래의 진행 여부에서 **파생**한다 — 같은 일을 하는 플래그를 따로 두면 둘이 어긋나고,
     * 작업이 끝났는데 차단만 남거나 그 반대가 된다.
     *
     * 읽기·미리보기·dry-run 은 세지 않는다. 저장소·파일시스템을 바꾸지 않으므로 끊겨도 잃는 것이 없다.
     */
    val isMutating: Boolean
        get() = applyJob.isRunning || saveJob.isRunning

    /** 커밋 모드에 필요한 값이 갖춰졌는가. 다른 모드에서는 늘 참이다. */
    private val commitInputSatisfied: Boolean
        get() = applyMode != PatchApplyModeChoice.CREATE_COMMIT ||
            hasPatchCommitMetadata ||
            (commitAuthorName.isNotBlank() && commitAuthorEmail.isNotBlank() && commitMessage.isNotBlank())

    fun load(limit: Int = PATCH_COMMIT_PAGE_SIZE) {
        launchJob(commitsJob, PatchCommitsUiState.Loading) {
            try {
                PatchCommitsUiState.Loaded(actions.loadRecentCommits(limit))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                PatchCommitsUiState.Failed(failure)
            }
        }
    }

    fun selectSource(next: PatchSource) {
        source = next
        resetPreparation()
    }

    fun selectScope(next: WorkingTreeScope) {
        workingTreeScope = next
        resetPreparation()
    }

    fun selectFormat(next: PatchFormat) {
        format = next
        saveJob.reset()
    }

    /** 구간의 시작점을 고른다. 이미 고른 것을 다시 누르면 선택이 풀린다. */
    fun anchorCommit(index: Int) {
        selectedCommitRange = if (selectedCommitRange == index..index) null else index..index
        resetPreparation()
    }

    /** 시작점에서 [index] 까지의 **연속 구간**으로 넓힌다. 시작점이 없으면 그 자리를 시작점으로 삼는다. */
    fun extendCommitSelectionTo(index: Int) {
        val anchor = selectedCommitRange?.first
        selectedCommitRange = if (anchor == null) index..index else minOf(anchor, index)..maxOf(anchor, index)
        resetPreparation()
    }

    /**
     * 생성한다. 저장은 하지 않는다 — 무엇이 들어가는지 먼저 보여 준다.
     *
     * 무엇을 만들지는 **시작할 때 집어**([exportRequest]) 작업에 가둔다. 작업 도중 대상·범위가 바뀌어도
     * 캡처본만 읽으므로, 고른 적 없는 범위의 패치가 만들어지지 않는다.
     */
    fun prepare() {
        val request = exportRequest() ?: return
        saveJob.reset()
        launchJob(createJob, PatchPreparationUiState.Preparing) {
            try {
                val export = export(request)
                // 경로 추출은 패치 전체를 훑는다 — 큰 패치에서 UI 스레드에 두면 화면이 멈춘다.
                PatchPreparationUiState.Ready(withContext(ioDispatcher) { export.toPreparation() })
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                PatchPreparationUiState.Failed(failure)
            }
        }
    }

    /**
     * 생성 결과를 저장한다. 형태에 따라 대상이 다르다 — 단일 통합은 파일 하나, 커밋당 파일은 디렉터리다.
     *
     * 같은 이름이 이미 있으면 쓰기 전에 [PatchSaveUiState.ConfirmOverwrite] 로 멈춘다.
     *
     * 저장이 이미 돌고 있으면 **대화상자도 열지 않고** 돌아간다 — 사용자가 고른 대상을 받아 두고
     * 버리면, 고른 곳에 저장된 줄 알게 된다.
     *
     * 형식은 **시작할 때 한 번 집어** 대상 선택과 대상 계산이 같은 값만 본다 — 대상 계산은 [ioDispatcher]
     * 로 넘어가 있어, 그 사이 형식을 바꾸면 파일을 고르게 해 놓고 디렉터리 대상으로 쓰거나 그 반대가 된다.
     */
    fun requestSave() {
        val ready = readyToSave() ?: return
        val chosenFormat = format
        // 대화상자는 부른 스레드에서 연다 — 창을 띄우는 것은 사용자 조작이고, 무거운 것은 그 뒤의 쓰기다.
        val destination = chooseSaveDestination(chosenFormat) ?: return
        launchJob(saveJob, PatchSaveUiState.Saving) {
            val target = withContext(ioDispatcher) { saveTargetOf(ready.export, destination, chosenFormat) }
            val existingNames = withContext(ioDispatcher) {
                target.entries.filter { entry -> files.exists(entry.path) }.map { it.path.fileName.toString() }
            }
            if (existingNames.isEmpty()) {
                writeAll(target)
            } else {
                PatchSaveUiState.ConfirmOverwrite(target, existingNames)
            }
        }
    }

    /** 경고를 본 사용자가 덮어쓰기를 명시적으로 확인했다. */
    fun confirmOverwrite() {
        val pending = (save as? PatchSaveUiState.ConfirmOverwrite)?.target ?: return
        launchJob(saveJob, PatchSaveUiState.Saving) { writeAll(pending) }
    }

    /** 저장할 것이 준비돼 있는가. 앞선 저장이 도는 중이면 `null` 이라 대화상자조차 열리지 않는다. */
    private fun readyToSave(): PatchPreparation? =
        if (saveJob.isRunning) null else (preparation as? PatchPreparationUiState.Ready)?.preparation

    fun cancelOverwrite() {
        saveJob.reset()
    }

    /** 파일 선택 대화상자로 패치를 받는다. 취소하면 아무것도 바뀌지 않는다. */
    fun choosePatchFile() {
        files.chooseOpenFile()?.let(::acceptPatchFile)
    }

    /** OS 드롭을 받는다. 거부 사유는 예외가 아니라 화면 상태로 남는다. */
    fun acceptDrop(locations: List<String>) {
        when (val drop = patchDropOf(locations)) {
            is PatchDrop.Accepted -> acceptPatchFile(drop.path)
            is PatchDrop.Rejected -> {
                dropRejection = drop.reason
                // 앞선 읽기 실패 안내는 지운다 — 이번 거부 사유와 겹치면 무엇 때문인지 흐려진다.
                if (patchRead is PatchReadUiState.Failed) readJob.reset()
            }
        }
    }

    fun selectApplyMode(next: PatchApplyModeChoice) {
        applyMode = next
    }

    fun changeCommitAuthorName(value: String) {
        commitAuthorName = value
    }

    fun changeCommitAuthorEmail(value: String) {
        commitAuthorEmail = value
    }

    fun changeCommitMessage(value: String) {
        commitMessage = value
    }

    fun showPreviewViewMode(mode: DiffViewMode) {
        previewViewMode = mode
    }

    /**
     * 고른 모드로 실제 적용한다. dry-run 이 통과하지 않았으면 아무것도 하지 않는다.
     *
     * 패치와 모드를 **시작할 때 집어** 작업에 가둔다 — 적용이 도는 사이 화면에서 다른 패치를 골라도
     * 시작한 적용은 자기 패치를 끝까지 쓴다.
     */
    fun apply() {
        val patch = selectedPatch ?: return
        if (!canApply) return
        val mode = applyModeOf()
        launchJob(applyJob, PatchOutcomeUiState.Running) { runOutcome { actions.apply(patch.bytes, mode) } }
    }

    /**
     * 고른 파일을 읽어 미리보기·메타데이터까지 만들고, 이어서 dry-run 을 돌린다.
     *
     * 읽기와 파싱은 [ioDispatcher] 에서 한다 — 1 MiB 패치를 UI 스레드에서 훑으면 화면이 멈춘다.
     * 그동안 [patchRead] 는 [PatchReadUiState.Reading] 이라 화면이 진행 중을 보여 준다.
     *
     * 앞선 적용이 도는 중이면 그 진행 상태는 지우지 않는다 ([PatchJobLane.reset]) — 저장소를 바꾸는
     * 작업이 도는데 화면만 비면, 사용자는 아무 일도 없었다고 읽는다.
     */
    private fun acceptPatchFile(path: Path) {
        dropRejection = null
        dryRunJob.reset()
        applyJob.reset()
        launchJob(readJob, PatchReadUiState.Reading, then = ::startDryRun) {
            try {
                PatchReadUiState.Loaded(withContext(ioDispatcher) { readPatch(path) })
            } catch (failure: IOException) {
                // 읽지 못한 것을 "빈 패치" 로 접으면 화면이 변경 없음으로 안내한다 — 사유를 그대로 올린다.
                PatchReadUiState.Failed(failure)
            }
        }
    }

    /** 적용 전 검사는 **워킹트리만** 모드로 돈다 — 커밋 메타데이터가 아직 없어도 붙는지 알 수 있다. */
    private fun startDryRun(read: PatchReadUiState) {
        val patch = (read as? PatchReadUiState.Loaded)?.patch ?: return
        launchJob(dryRunJob, PatchOutcomeUiState.Running) {
            runOutcome { actions.dryRun(patch.bytes, ApplyMode.WorkingTreeOnly) }
        }
    }

    /**
     * 비동기 작업의 **단일 진입점**. 조회·생성 준비·패치 읽기·dry-run·적용·저장이 전부 이것만 거친다.
     *
     * 1. **시작 스냅샷** — 작업이 쓰는 화면 값은 호출부가 이 함수를 부르기 전에 집어 [work] 에 가둔다.
     *    작업 도중 화면이 바뀌어도 캡처본만 읽는다.
     * 2. **세대 확인** — 결과는 [lane] 이 아직 그 작업을 기다릴 때만 앉는다. 버려진 결과는 [then] 도
     *    태우지 않는다 — 지난 읽기가 지금 패치의 검사를 시작하면 안 된다.
     * 3. **수명 추적** — 도는 동안 [PatchJobLane.isRunning] 이 서고, 끝나면 `finally` 에서 내린다.
     *    [PatchJobMode.EXCLUSIVE] 갈래는 도는 중이면 아예 시작하지 않는다.
     *
     * **새 비동기 경로는 반드시 이 함수를 거친다.** [CoroutineScope.launch] 를 직접 부르면 세대·수명·
     * 진행 상태가 다시 흩어지고, 같은 계열의 결함이 그 지점에서 새로 난다.
     */
    private fun <S : Any> launchJob(
        lane: PatchJobLane<S>,
        running: S,
        then: (S) -> Unit = {},
        work: suspend () -> S,
    ) {
        val token = lane.begin(running) ?: return
        scope.launch {
            try {
                val result = work()
                if (lane.complete(token, result)) then(result)
            } finally {
                lane.finish(token)
            }
        }
    }

    private fun readPatch(path: Path): SelectedPatch {
        val bytes = files.read(path)
        return SelectedPatch(
            path = path,
            bytes = bytes,
            preview = actions.preview(bytes),
            metadata = actions.commitMetadataOf(bytes),
        )
    }

    private suspend fun runOutcome(action: suspend () -> ApplyOutcome): PatchOutcomeUiState = try {
        PatchOutcomeUiState.Completed(action())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (violation: UndineException.StateViolation) {
        PatchOutcomeUiState.Refused(violation)
    } catch (failure: UndineException) {
        PatchOutcomeUiState.Failed(failure)
    }

    private fun applyModeOf(): ApplyMode = when (applyMode) {
        PatchApplyModeChoice.WORKING_TREE_ONLY -> ApplyMode.WorkingTreeOnly
        PatchApplyModeChoice.INDEX -> ApplyMode.Index
        // 패치 메타데이터가 있으면 게이트웨이가 그것을 우선하므로 입력은 비워 보낸다.
        PatchApplyModeChoice.CREATE_COMMIT -> ApplyMode.CreateCommit(
            author = authorInput(),
            message = commitMessage.takeIf(String::isNotBlank),
        )
    }

    private fun authorInput(): Person? =
        if (commitAuthorName.isBlank() || commitAuthorEmail.isBlank()) {
            null
        } else {
            Person(commitAuthorName.trim(), commitAuthorEmail.trim())
        }

    /** 지금 화면이 만들 수 있는 생성 요청. 커밋 모드에서 선택이 비면 `null` 이라 생성이 막힌다. */
    private fun exportRequest(): PatchExportRequest? = when (source) {
        PatchSource.WORKING_TREE -> PatchExportRequest.WorkingTree(workingTreeScope)
        PatchSource.COMMITS -> commitRangeOf(selectedCommits)?.let(PatchExportRequest::Commits)
    }

    private suspend fun export(request: PatchExportRequest): PatchExport = when (request) {
        is PatchExportRequest.WorkingTree -> actions.exportWorkingTree(request.scope)
        is PatchExportRequest.Commits -> actions.exportCommits(request.range)
    }

    private fun PatchExport.toPreparation(): PatchPreparation =
        PatchPreparation(export = this, paths = patchPathsOf(combined), totalBytes = combined.size)

    /** 형식을 인자로 받는다 — [requestSave] 가 집어 둔 값만 보고, 그 사이 바뀐 [format] 을 읽지 않는다. */
    private fun chooseSaveDestination(chosenFormat: PatchFormat): Path? = when (chosenFormat) {
        PatchFormat.COMBINED -> files.chooseSaveFile(COMBINED_PATCH_FILE_NAME)
        PatchFormat.PER_COMMIT -> files.chooseDirectory()
    }

    private fun saveTargetOf(
        export: PatchExport,
        destination: Path,
        chosenFormat: PatchFormat,
    ): PatchSaveTarget = when (chosenFormat) {
        PatchFormat.COMBINED -> PatchSaveTarget(
            listOf(PatchFileWrite(destination, export.combined)),
            destination.toString(),
        )

        PatchFormat.PER_COMMIT -> PatchSaveTarget(export.toPerCommitWrites(destination), destination.toString())
    }

    private fun PatchExport.toPerCommitWrites(directory: Path): List<PatchFileWrite> =
        perCommit.mapIndexed { index, commitPatch ->
            val summary = actions.commitMetadataOf(commitPatch.bytes).message
            PatchFileWrite(directory.resolve(perCommitPatchFileName(index + 1, summary)), commitPatch.bytes)
        }

    /**
     * 전부 쓰거나 아무것도 남기지 않는다 — 중간에 실패하면 [writeAllOrRollback] 이 새 파일을 지우고
     * 덮어쓴 파일을 원래 내용으로 되돌린다. 되돌리기까지 실패하면 그 사실을 결과에 실어 화면에 알린다.
     * 지우지 못한 임시 파일도 같은 방식으로 올린다 — 남은 파일은 사용자가 직접 치워야 한다.
     */
    private suspend fun writeAll(target: PatchSaveTarget): PatchSaveUiState =
        when (val result = withContext(ioDispatcher) { files.writeAllOrRollback(target.entries) }) {
            PatchWriteResult.Written -> PatchSaveUiState.Saved(target.label)
            is PatchWriteResult.Failed -> PatchSaveUiState.Failed(
                reason = result.failure.message ?: result.failure.javaClass.simpleName,
                rollbackFailed = result.rollbackFailure != null,
                leftoverTemporaries = result.leftoverTemporaries.map(Path::toString),
            )
        }

    /** 선택이 바뀌었다. 갈래를 되돌려 **도는 중인 생성의 결과**까지 함께 버린다. */
    private fun resetPreparation() {
        createJob.reset()
        saveJob.reset()
    }
}

/**
 * 무엇을 대상으로 패치를 만들 것인가 — 생성이 시작될 때 집는 스냅샷.
 *
 * 화면 값을 작업 안에서 다시 읽지 않기 위해 존재한다. 도중에 범위를 바꾸면 사용자가 고른 적 없는
 * 범위의 패치가 만들어진다.
 */
private sealed interface PatchExportRequest {
    data class WorkingTree(val scope: WorkingTreeScope) : PatchExportRequest
    data class Commits(val range: CommitRange) : PatchExportRequest
}

/**
 * 화면 수명 동안 유지되는 홀더.
 *
 * **[sessionKey] 도 키다** (결정 C6). 활성 저장소가 바뀌면 홀더를 새로 만들어 선택·미리보기·dry-run·
 * 적용 결과를 통째로 버린다 — 앞선 저장소에서 통과한 검사를 다음 저장소에 물려주면, 사용자가 본
 * "적용 가능" 이 지금 활성인 저장소에 대한 판정이 아니게 된다.
 */
@Composable
fun rememberPatchState(actions: PatchActions, files: PatchFiles, sessionKey: RepositorySessionKey?): PatchState {
    val scope = rememberCoroutineScope()
    return remember(actions, files, sessionKey) { PatchState(actions, files, scope) }
}
