package dev.undine.presentation.patch

import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.CommitPatch
import dev.undine.domain.patch.CommitRange
import dev.undine.domain.patch.PatchExport
import dev.undine.domain.patch.UnsupportedReason
import dev.undine.domain.patch.WorkingTreeScope
import dev.undine.presentation.AppDestination
import dev.undine.presentation.AppNavigationState
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private val COMBINED_PATCH = """
    diff --git a/src/Main.kt b/src/Main.kt
    --- a/src/Main.kt
    +++ b/src/Main.kt
    @@ -1,1 +1,1 @@
    -old
    +new
""".trimIndent().toByteArray()

private val ENGLISH_FORMATTED_PATCH = """
    From: A B <a@b.c>
    Subject: Fix the parser

    ---
    diff --git a/src/Main.kt b/src/Main.kt
    --- a/src/Main.kt
    +++ b/src/Main.kt
    @@ -1,1 +1,1 @@
    -old
    +new
""".trimIndent().toByteArray()

private val FORMATTED_PATCH = """
    From: 조봉준 <biuea3866@gmail.com>
    Subject: 첫 커밋

    ---
    diff --git a/src/Main.kt b/src/Main.kt
    --- a/src/Main.kt
    +++ b/src/Main.kt
    @@ -1,1 +1,1 @@
    -old
    +new
""".trimIndent().toByteArray()

/**
 * Patch 화면 상태 홀더 — 생성 범위·형태·커밋 구간, 적용 전 dry-run, 모드별 사전 차단, 저장 대상.
 *
 * 파일 대화상자와 쓰기는 [FakePatchFiles] 로 대체한다. 실제 AWT 를 열면 CI 가 사람 조작을 기다리며 멈춘다.
 */
@Suppress("LargeClass") // 생성·적용 두 흐름의 화면 전이를 한 스펙이 본다 — 티켓 케이스와 1:1이다.
class PatchStateSpec : FunSpec({

    test("생성 범위 기본값은 전체 변경이고 세 범위를 모두 고를 수 있다") {
        val actions = FakePatchActions()
        val state = state(actions)

        state.workingTreeScope shouldBe WorkingTreeScope.ALL

        WorkingTreeScope.entries.forEach { scope ->
            state.selectScope(scope)
            state.prepare()
        }

        actions.exportedScopes shouldContainExactly WorkingTreeScope.entries.toList()
    }

    test("적용 모드 기본값은 워킹트리만이다") {
        state(FakePatchActions()).applyMode shouldBe PatchApplyModeChoice.WORKING_TREE_ONLY
    }

    test("생성 전에 포함될 파일 목록과 총 크기를 보여 준다") {
        val export = PatchExport(perCommit = emptyList(), combined = COMBINED_PATCH)
        val state = state(FakePatchActions(exportResult = export))

        state.prepare()

        val ready = state.preparation.shouldBeInstanceOf<PatchPreparationUiState.Ready>().preparation
        ready.paths shouldContainExactly listOf("src/Main.kt")
        ready.totalBytes shouldBe COMBINED_PATCH.size
    }

    test("생성이 오래 걸리면 진행 중 상태를 노출하고 실패를 빈 결과로 접지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val running = state(FakePatchActions(exportGate = gate))

        running.prepare()
        running.preparation shouldBe PatchPreparationUiState.Preparing
        gate.complete(Unit)
        running.preparation.shouldBeInstanceOf<PatchPreparationUiState.Ready>()

        val failing = state(FakePatchActions(exportFailure = UndineException.GitOperationFailed("export")))
        failing.prepare()
        failing.preparation.shouldBeInstanceOf<PatchPreparationUiState.Failed>()
            .failure.shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "export"
    }

    test("커밋 선택이 비면 생성이 막히고, 연속 구간을 고르면 최신·가장 오래된 첫 부모로 범위가 된다") {
        val actions = FakePatchActions(commits = listOf(commit(9, 8), commit(8, 7), commit(7, 6)))
        val state = state(actions)
        state.load()
        state.selectSource(PatchSource.COMMITS)

        state.canPrepare shouldBe false
        state.prepare()
        actions.exportedRanges.isEmpty() shouldBe true

        state.anchorCommit(0)
        state.extendCommitSelectionTo(2)
        state.canPrepare shouldBe true
        state.prepare()

        actions.exportedRanges.single() shouldBe CommitRange(from = commitId(6), to = commitId(9))
    }

    test("고른 커밋을 다시 누르면 선택이 풀려 생성이 다시 막힌다") {
        val state = state(FakePatchActions(commits = listOf(commit(2, 1))))
        state.load()
        state.selectSource(PatchSource.COMMITS)

        state.anchorCommit(0)
        state.canPrepare shouldBe true

        state.anchorCommit(0)
        state.selectedCommits.isEmpty() shouldBe true
        state.canPrepare shouldBe false
    }

    test("단일 통합 형태는 patch 확장자를 기본값으로 하는 파일 저장 대화상자를 쓴다") {
        val files = FakePatchFiles(saveChoice = path("/tmp/out.patch"))
        val state = state(FakePatchActions(exportResult = PatchExport(emptyList(), COMBINED_PATCH)), files)

        state.prepare()
        state.requestSave()

        files.saveDefaults shouldContainExactly listOf(COMBINED_PATCH_FILE_NAME)
        files.directoryRequests shouldBe 0
        files.written.single().first shouldBe path("/tmp/out.patch")
        state.save.shouldBeInstanceOf<PatchSaveUiState.Saved>()
    }

    test("커밋당 파일 형태는 디렉터리를 고르고 순번 이름으로 저장한다") {
        val export = PatchExport(
            perCommit = listOf(
                CommitPatch(commitId(1), ENGLISH_FORMATTED_PATCH),
                CommitPatch(commitId(2), COMBINED_PATCH),
            ),
            combined = COMBINED_PATCH,
        )
        val files = FakePatchFiles(directoryChoice = path("/tmp/patches"))
        val state = state(FakePatchActions(exportResult = export), files)

        state.selectFormat(PatchFormat.PER_COMMIT)
        state.prepare()
        state.requestSave()

        files.directoryRequests shouldBe 1
        files.saveDefaults.isEmpty() shouldBe true
        // 메타데이터가 없는 두 번째 패치는 요약을 지어내지 않고 순번만 쓴다.
        files.written.map { it.first.toString() } shouldContainExactly listOf(
            "/tmp/patches/0001-fix-the-parser.patch",
            "/tmp/patches/0002.patch",
        )
    }

    test("같은 이름이 이미 있으면 저장 전에 확인하고, 취소하면 쓰지 않는다") {
        val files = FakePatchFiles(saveChoice = path("/tmp/out.patch"), existing = setOf(path("/tmp/out.patch")))
        val state = state(FakePatchActions(exportResult = PatchExport(emptyList(), COMBINED_PATCH)), files)

        state.prepare()
        state.requestSave()

        state.save.shouldBeInstanceOf<PatchSaveUiState.ConfirmOverwrite>()
            .existingNames shouldContainExactly listOf("out.patch")
        files.written.isEmpty() shouldBe true

        state.cancelOverwrite()
        files.written.isEmpty() shouldBe true

        state.requestSave()
        state.confirmOverwrite()
        files.written.single().first shouldBe path("/tmp/out.patch")
    }

    test("저장 실패를 성공으로 접지 않는다") {
        val files = FakePatchFiles(saveChoice = path("/tmp/out.patch"), writeFailure = IOException("읽기 전용"))
        val state = state(FakePatchActions(exportResult = PatchExport(emptyList(), COMBINED_PATCH)), files)

        state.prepare()
        state.requestSave()

        state.save.shouldBeInstanceOf<PatchSaveUiState.Failed>()
    }

    test("파일을 고르면 dry-run 이 돌고 미리보기와 메타데이터가 함께 준비된다") {
        val files = FakePatchFiles(
            openChoice = path("/tmp/fix.patch"),
            contents = mapOf(path("/tmp/fix.patch") to FORMATTED_PATCH),
        )
        val actions = FakePatchActions()
        val state = state(actions, files)

        state.choosePatchFile()

        state.selectedPatch.shouldNotBeNull().name shouldBe "fix.patch"
        state.dryRun.shouldBeInstanceOf<PatchOutcomeUiState.Completed>().outcome shouldBe
            ApplyOutcome.Applied(listOf("src/Main.kt"))
        actions.dryRunModes shouldContainExactly listOf(ApplyMode.WorkingTreeOnly)
        state.hasPatchCommitMetadata shouldBe true
        state.canApply shouldBe true
    }

    test("드롭으로 받은 파일도 같은 경로를 타고, 여러 파일 드롭은 사유를 남기고 받지 않는다") {
        val files = FakePatchFiles(contents = mapOf(path("/tmp/fix.patch") to COMBINED_PATCH))
        val state = state(FakePatchActions(), files)

        state.acceptDrop(listOf("/tmp/fix.patch"))
        state.selectedPatch.shouldNotBeNull().name shouldBe "fix.patch"
        state.dropRejection shouldBe null

        state.acceptDrop(listOf("/tmp/a.patch", "/tmp/b.patch"))
        state.dropRejection shouldBe PatchDrop.Reason.MULTIPLE_FILES
    }

    test("패치를 읽지 못하면 빈 패치가 아니라 읽기 실패로 남고 적용이 막힌다") {
        val files = FakePatchFiles(openChoice = path("/tmp/fix.patch"), readFailure = IOException("권한 없음"))
        val state = state(FakePatchActions(), files)

        state.choosePatchFile()

        state.readFailure.shouldNotBeNull()
        state.selectedPatch shouldBe null
        state.canApply shouldBe false
    }

    test("충돌·미지원·거부·변경 없음은 각각 남고 적용을 막는다") {
        val conflicted = loaded(FakePatchActions(dryRunOutcome = ApplyOutcome.Conflicted(listOf("src/Main.kt"))))
        conflicted.dryRun.shouldBeInstanceOf<PatchOutcomeUiState.Completed>().outcome shouldBe
            ApplyOutcome.Conflicted(listOf("src/Main.kt"))
        conflicted.canApply shouldBe false

        val threeWay = loaded(
            FakePatchActions(dryRunOutcome = ApplyOutcome.Unsupported(UnsupportedReason.THREE_WAY_REQUIRED)),
        )
        threeWay.canApply shouldBe false

        val refused = loaded(FakePatchActions(dryRunFailure = UndineException.StateViolation("경로 이탈")))
        refused.dryRun.shouldBeInstanceOf<PatchOutcomeUiState.Refused>().violation.detail shouldBe "경로 이탈"
        refused.canApply shouldBe false

        val empty = loaded(FakePatchActions(dryRunOutcome = ApplyOutcome.NoChange))
        empty.dryRun.shouldBeInstanceOf<PatchOutcomeUiState.Completed>().outcome shouldBe ApplyOutcome.NoChange
        empty.canApply shouldBe false
    }

    test("dry-run 조회 실패를 거부와 구분한다") {
        val failed = loaded(FakePatchActions(dryRunFailure = UndineException.GitOperationFailed("dryRun")))

        failed.dryRun.shouldBeInstanceOf<PatchOutcomeUiState.Failed>()
        failed.canApply shouldBe false
    }

    test("적용은 고른 모드를 그대로 게이트웨이에 넘긴다") {
        val actions = FakePatchActions()
        val state = loaded(actions)

        state.selectApplyMode(PatchApplyModeChoice.INDEX)
        state.apply()

        actions.appliedModes shouldContainExactly listOf(ApplyMode.Index)
        state.applyResult.shouldBeInstanceOf<PatchOutcomeUiState.Completed>().outcome shouldBe
            ApplyOutcome.Applied(listOf("src/Main.kt"))
    }

    test("커밋 모드는 메타데이터가 없고 입력도 비면 미리 막힌다") {
        val state = loaded(FakePatchActions(), patch = COMBINED_PATCH)

        state.selectApplyMode(PatchApplyModeChoice.CREATE_COMMIT)
        state.hasPatchCommitMetadata shouldBe false
        state.commitAuthorName shouldBe ""
        state.commitMessage shouldBe ""
        state.canApply shouldBe false

        state.changeCommitAuthorName("조봉준")
        state.changeCommitAuthorEmail("biuea3866@gmail.com")
        state.canApply shouldBe false

        state.changeCommitMessage("패치를 적용한다")
        state.canApply shouldBe true
    }

    test("패치에 메타데이터가 있으면 입력 없이도 커밋 모드로 적용할 수 있다") {
        val actions = FakePatchActions()
        val state = loaded(actions, patch = FORMATTED_PATCH)

        state.selectApplyMode(PatchApplyModeChoice.CREATE_COMMIT)
        state.canApply shouldBe true

        state.apply()

        // 게이트웨이가 패치 메타데이터를 우선하므로 화면은 빈 값을 넘긴다.
        actions.appliedModes shouldContainExactly listOf(ApplyMode.CreateCommit(author = null, message = null))
    }

    test("여러 파일 저장이 중간에 실패하면 덮어쓴 파일이 원래 내용으로 돌아온다") {
        val first = path("/tmp/patches/0001-fix-the-parser.patch")
        val files = FakePatchFiles(
            directoryChoice = path("/tmp/patches"),
            contents = mapOf(first to EXISTING_FILE),
            writeFailure = IOException("디스크 가득 참"),
            failWriteAt = 2,
        )
        val state = state(FakePatchActions(exportResult = perCommitExport()), files)

        state.selectFormat(PatchFormat.PER_COMMIT)
        state.prepare()
        state.requestSave()
        state.confirmOverwrite()

        state.save.shouldBeInstanceOf<PatchSaveUiState.Failed>().rollbackFailed shouldBe false
        files.contentOf(first)?.decodeToString() shouldBe EXISTING_FILE.decodeToString()
    }

    test("여러 파일 저장이 중간에 실패하면 앞서 만든 새 파일이 남지 않는다") {
        val files = FakePatchFiles(
            directoryChoice = path("/tmp/patches"),
            writeFailure = IOException("디스크 가득 참"),
            failWriteAt = 2,
        )
        val state = state(FakePatchActions(exportResult = perCommitExport()), files)

        state.selectFormat(PatchFormat.PER_COMMIT)
        state.prepare()
        state.requestSave()

        state.save.shouldBeInstanceOf<PatchSaveUiState.Failed>()
        files.contentOf(path("/tmp/patches/0001-fix-the-parser.patch")) shouldBe null
    }

    test("되돌리기까지 실패하면 폴더 상태가 어떤지 함께 남긴다") {
        val files = FakePatchFiles(
            directoryChoice = path("/tmp/patches"),
            writeFailure = IOException("디스크 가득 참"),
            failWriteAt = 2,
            deleteFailure = IOException("권한 없음"),
        )
        val state = state(FakePatchActions(exportResult = perCommitExport()), files)

        state.selectFormat(PatchFormat.PER_COMMIT)
        state.prepare()
        state.requestSave()

        state.save.shouldBeInstanceOf<PatchSaveUiState.Failed>().rollbackFailed shouldBe true
    }

    test("임시 파일까지 지우지 못했으면 어떤 파일이 남았는지 화면에 전달한다") {
        val leftover = path("/tmp/patches/undine-patch1234.tmp")
        // 경계가 정리 실패를 원래 실패에 실어 올린다 — 화면이 그 사실을 받는지 여기서 본다.
        val failure = IOException("디스크 가득 참").apply {
            addSuppressed(PatchTemporaryFileLeftException(leftover, IOException("권한 없음")))
        }
        val files = FakePatchFiles(directoryChoice = path("/tmp/patches"), writeFailure = failure, failWriteAt = 2)
        val state = state(FakePatchActions(exportResult = perCommitExport()), files)

        state.selectFormat(PatchFormat.PER_COMMIT)
        state.prepare()
        state.requestSave()

        state.save.shouldBeInstanceOf<PatchSaveUiState.Failed>()
            .leftoverTemporaries shouldContainExactly listOf(leftover.toString())
    }

    test("임시 파일을 지웠으면 남은 파일을 화면에 알리지 않는다") {
        val files = FakePatchFiles(
            directoryChoice = path("/tmp/patches"),
            writeFailure = IOException("디스크 가득 참"),
            failWriteAt = 2,
        )
        val state = state(FakePatchActions(exportResult = perCommitExport()), files)

        state.selectFormat(PatchFormat.PER_COMMIT)
        state.prepare()
        state.requestSave()

        state.save.shouldBeInstanceOf<PatchSaveUiState.Failed>().leftoverTemporaries.shouldBeEmpty()
    }

    test("패치를 읽는 동안 읽는 중 상태를 남긴다") {
        val target = path("/tmp/fix.patch")
        val seen = mutableListOf<PatchReadUiState>()
        lateinit var holder: PatchState
        val files = FakePatchFiles(
            openChoice = target,
            contents = mapOf(target to COMBINED_PATCH),
            onRead = { seen += holder.patchRead },
        )
        holder = state(FakePatchActions(), files)

        holder.choosePatchFile()

        seen shouldContainExactly listOf(PatchReadUiState.Reading)
        holder.patchRead.shouldBeInstanceOf<PatchReadUiState.Loaded>()
    }

    test("저장하는 동안 저장 중 상태를 남긴다") {
        val seen = mutableListOf<PatchSaveUiState>()
        lateinit var holder: PatchState
        val files = FakePatchFiles(saveChoice = path("/tmp/out.patch"), onWrite = { seen += holder.save })
        holder = state(FakePatchActions(exportResult = PatchExport(emptyList(), COMBINED_PATCH)), files)

        holder.prepare()
        holder.requestSave()

        seen shouldContainExactly listOf(PatchSaveUiState.Saving)
        holder.save.shouldBeInstanceOf<PatchSaveUiState.Saved>()
    }

    test("저장이 도는 중에 들어온 두 번째 저장 요청은 시작되지 않는다") {
        lateinit var holder: PatchState
        var reentered = false
        val files = FakePatchFiles(
            directoryChoice = path("/tmp/patches"),
            // 첫 파일을 쓰는 도중에 저장을 한 번 더 요청한다 — 두 저장이 겹치면 한쪽의 되돌리기가
            // 다른 쪽이 만든 파일을 지운다.
            onWrite = {
                if (!reentered) {
                    reentered = true
                    holder.requestSave()
                }
            },
        )
        holder = state(FakePatchActions(exportResult = perCommitExport()), files)

        holder.selectFormat(PatchFormat.PER_COMMIT)
        holder.prepare()
        holder.requestSave()

        reentered shouldBe true
        // 두 번째 요청은 대화상자조차 열지 않는다 — 고른 대상을 받아 두고 버리지 않는다.
        files.directoryRequests shouldBe 1
        files.written.map { it.first.toString() } shouldContainExactly listOf(
            "/tmp/patches/0001-fix-the-parser.patch",
            "/tmp/patches/0002.patch",
        )
        holder.save.shouldBeInstanceOf<PatchSaveUiState.Saved>()
    }

    test("선택이 바뀐 뒤 늦게 끝난 생성 결과는 화면에 앉지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val state = state(FakePatchActions(exportGate = gate, exportResult = PatchExport(emptyList(), COMBINED_PATCH)))

        state.prepare()
        state.preparation shouldBe PatchPreparationUiState.Preparing

        state.selectScope(WorkingTreeScope.STAGED)
        state.preparation shouldBe PatchPreparationUiState.Idle

        gate.complete(Unit)

        // 앞선 범위로 만든 패치가 지금 고른 범위의 결과인 척 앉으면 안 된다.
        state.preparation shouldBe PatchPreparationUiState.Idle
    }

    test("다른 패치를 고르면 늦게 끝난 앞선 읽기가 새 패치를 덮지 않는다") {
        val first = path("/tmp/first.patch")
        val second = path("/tmp/second.patch")
        val io = DeferFirstDispatcher()
        val files = FakePatchFiles(
            openChoice = first,
            contents = mapOf(first to COMBINED_PATCH, second to FORMATTED_PATCH),
        )
        val holder = state(FakePatchActions(), files, ioDispatcher = io)

        // 첫 읽기는 붙잡혀 있고, 그 사이 고른 두 번째 패치가 먼저 끝난다.
        holder.choosePatchFile()
        holder.patchRead shouldBe PatchReadUiState.Reading
        holder.acceptDrop(listOf(second.toString()))
        holder.selectedPatch.shouldNotBeNull().name shouldBe "second.patch"

        io.releaseFirst()

        // 뒤늦게 끝난 첫 읽기가 지금 고른 패치를 덮으면 사용자는 고르지 않은 패치를 적용하게 된다.
        holder.selectedPatch.shouldNotBeNull().name shouldBe "second.patch"
        holder.hasPatchCommitMetadata shouldBe true
    }

    test("대화상자를 연 뒤 형식이 바뀌어도 저장 대상은 열었을 때의 형식을 따른다") {
        // 단일 통합으로 파일을 고르게 해 놓고, 대상 계산 직전에 커밋당 형식으로 바꾼다.
        val io = HookedDispatcher()
        val files = FakePatchFiles(saveChoice = path("/tmp/out.patch"), directoryChoice = path("/tmp/patches"))
        val holder = state(FakePatchActions(exportResult = perCommitExport()), files, ioDispatcher = io)

        holder.prepare()
        io.onNextDispatch = { holder.selectFormat(PatchFormat.PER_COMMIT) }
        holder.requestSave()

        // 고른 것은 파일 하나다 — 그 경로를 디렉터리 삼아 커밋당 파일을 쏟아 넣으면 안 된다.
        files.written.map { it.first.toString() } shouldContainExactly listOf("/tmp/out.patch")
        files.directoryRequests shouldBe 0
        holder.save.shouldBeInstanceOf<PatchSaveUiState.Saved>()
    }

    test("커밋당 형식으로 디렉터리를 고른 뒤 단일 통합으로 바뀌어도 그 디렉터리에 커밋당 파일을 쓴다") {
        val io = HookedDispatcher()
        val files = FakePatchFiles(saveChoice = path("/tmp/out.patch"), directoryChoice = path("/tmp/patches"))
        val holder = state(FakePatchActions(exportResult = perCommitExport()), files, ioDispatcher = io)

        holder.selectFormat(PatchFormat.PER_COMMIT)
        holder.prepare()
        io.onNextDispatch = { holder.selectFormat(PatchFormat.COMBINED) }
        holder.requestSave()

        // 고른 것은 디렉터리다 — 그 자리에 통합 패치 파일 하나를 덮어써 폴더를 파일로 바꾸면 안 된다.
        files.written.map { it.first.toString() } shouldContainExactly listOf(
            "/tmp/patches/0001-fix-the-parser.patch",
            "/tmp/patches/0002.patch",
        )
        files.saveDefaults.isEmpty() shouldBe true
        holder.save.shouldBeInstanceOf<PatchSaveUiState.Saved>()
    }

    test("다른 패치를 고르면 늦게 끝난 앞선 dry-run 이 새 패치의 검사 결과를 덮지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val first = path("/tmp/first.patch")
        val second = path("/tmp/second.patch")
        val files = FakePatchFiles(
            openChoice = first,
            contents = mapOf(first to COMBINED_PATCH, second to FORMATTED_PATCH),
        )
        val actions = FakePatchActions(
            dryRunOutcomes = listOf(
                ApplyOutcome.Conflicted(listOf("src/First.kt")),
                ApplyOutcome.Applied(listOf("src/Second.kt")),
            ),
            dryRunGate = gate,
        )
        val holder = state(actions, files)

        // 첫 검사는 붙잡혀 있고, 그 사이 고른 두 번째 패치의 검사가 먼저 끝난다.
        holder.choosePatchFile()
        holder.dryRun shouldBe PatchOutcomeUiState.Running
        holder.acceptDrop(listOf(second.toString()))
        holder.dryRun.shouldBeInstanceOf<PatchOutcomeUiState.Completed>().outcome shouldBe
            ApplyOutcome.Applied(listOf("src/Second.kt"))

        gate.complete(Unit)

        // 앞선 패치의 충돌 판정이 지금 패치의 검사인 척 앉으면, 사용자는 고르지 않은 패치의 결과를 본다.
        holder.dryRun.shouldBeInstanceOf<PatchOutcomeUiState.Completed>().outcome shouldBe
            ApplyOutcome.Applied(listOf("src/Second.kt"))
        holder.canApply shouldBe true
    }

    test("적용이 도는 사이 다른 패치를 골라도 진행이 지워지지 않고 두 번째 적용이 막힌다") {
        val gate = CompletableDeferred<Unit>()
        val first = path("/tmp/first.patch")
        val second = path("/tmp/second.patch")
        val files = FakePatchFiles(
            openChoice = first,
            contents = mapOf(first to COMBINED_PATCH, second to FORMATTED_PATCH),
        )
        val actions = FakePatchActions(applyGate = gate)
        val holder = state(actions, files)

        holder.choosePatchFile()
        holder.apply()
        holder.applyResult shouldBe PatchOutcomeUiState.Running

        // 적용이 도는 사이 두 번째 패치를 고른다 — 그 검사는 통과한다.
        holder.acceptDrop(listOf(second.toString()))
        holder.dryRun.shouldBeInstanceOf<PatchOutcomeUiState.Completed>().outcome shouldBe
            ApplyOutcome.Applied(listOf("src/Main.kt"))

        // 도는 중인 적용의 진행을 지우면, 저장소가 바뀌고 있는데도 사용자는 아무 일도 없다고 읽는다.
        holder.applyResult shouldBe PatchOutcomeUiState.Running
        // 두 패치가 잇달아 저장소에 얹히면 안 된다 — 앞선 적용이 끝나기 전에는 열지 않는다.
        holder.canApply shouldBe false
        holder.apply()
        actions.appliedModes shouldContainExactly listOf(ApplyMode.WorkingTreeOnly)

        gate.complete(Unit)

        // 이미 저장소를 바꾼 작업의 결과는 버리지 않는다 — 무엇이 적용됐는지 알 길이 사라진다.
        holder.applyResult.shouldBeInstanceOf<PatchOutcomeUiState.Completed>().outcome shouldBe
            ApplyOutcome.Applied(listOf("src/Main.kt"))
        actions.appliedModes shouldContainExactly listOf(ApplyMode.WorkingTreeOnly)
    }

    // 화면을 떠나면 상태 홀더의 스코프가 취소된다 — 저장소를 이미 바꾸고 있는 작업이 사용자 모르게
    // 끊기고, 무엇이 남았는지 알릴 자리까지 함께 사라진다 (결정 C3).
    test("적용이 도는 동안 화면 이탈이 막히고, 끝나면 곧바로 풀린다") {
        val gate = CompletableDeferred<Unit>()
        val holder = loaded(FakePatchActions(applyGate = gate))
        val navigation = AppNavigationState(AppDestination.PATCH)
        navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON.takeIf { holder.isMutating } }

        holder.isMutating shouldBe false
        navigation.exitBlockedReason(AppDestination.REPOSITORY).shouldBeNull()

        holder.apply()

        holder.isMutating shouldBe true
        navigation.go(AppDestination.REPOSITORY)
        navigation.destination shouldBe AppDestination.PATCH

        gate.complete(Unit)

        // 차단은 진행 여부에서 파생한다 — 해제를 따로 기억하지 않으므로 "끝났는데 갇힌" 화면이 없다.
        holder.isMutating shouldBe false
        navigation.go(AppDestination.REPOSITORY)
        navigation.destination shouldBe AppDestination.REPOSITORY
    }

    test("저장이 도는 동안에도 화면 이탈이 막힌다 — 반쯤 쓰인 파일을 알릴 자리가 사라진다") {
        val navigation = AppNavigationState(AppDestination.PATCH)
        var blockedDuringWrite: String? = null
        val files = FakePatchFiles(
            saveChoice = path("/tmp/out.patch"),
            onWrite = { blockedDuringWrite = navigation.exitBlockedReason(AppDestination.REPOSITORY) },
        )
        val holder = state(FakePatchActions(exportResult = PatchExport(emptyList(), COMBINED_PATCH)), files)
        navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON.takeIf { holder.isMutating } }

        holder.prepare()
        holder.requestSave()

        blockedDuringWrite shouldBe EXIT_REASON
        holder.isMutating shouldBe false
        navigation.exitBlockedReason(AppDestination.REPOSITORY).shouldBeNull()
    }

    // 읽기·미리보기·dry-run 은 저장소도 디스크도 바꾸지 않는다. 끊겨도 잃는 것이 없으므로 막으면
    // 사용자를 이유 없이 화면에 묶어 두는 것이 된다.
    test("dry-run 이 도는 동안에는 이탈을 막지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val holder = loaded(FakePatchActions(dryRunGate = gate))

        holder.dryRun shouldBe PatchOutcomeUiState.Running
        holder.isMutating shouldBe false

        gate.complete(Unit)
    }

    test("최근 커밋 조회 실패는 빈 목록이 아니라 실패로 남는다") {
        val state = state(FakePatchActions(commitsFailure = UndineException.GitOperationFailed("history")))

        state.load()

        state.commits.shouldBeInstanceOf<PatchCommitsUiState.Failed>()
    }
})

private val EXISTING_FILE = "이미 있던 패치\n".toByteArray()

/** 이탈 차단 사유의 대역. 실제 문구는 `PatchStrings.exitBlocked` 가 갖고, 여기서는 전달만 확인한다. */
private const val EXIT_REASON = "적용·저장 중"

/**
 * 첫 작업만 붙잡아 두는 디스패처.
 *
 * 실제 `Dispatchers.IO` 에서는 큰 패치를 먼저 읽기 시작해도 작은 패치가 먼저 끝난다. 스레드 경쟁으로
 * 그 순서를 재현하면 테스트가 산발적으로 깨지므로, **끝나는 순서만** 뒤집어 결정적으로 만든다.
 */
private class DeferFirstDispatcher : CoroutineDispatcher() {

    private var held: Runnable? = null
    private var dispatches = 0

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches += 1
        if (dispatches == 1) held = block else block.run()
    }

    /** 붙잡아 둔 첫 작업을 뒤늦게 돌린다. */
    fun releaseFirst() {
        val pending = held
        held = null
        pending?.run()
    }
}

/**
 * 넘어온 작업을 돌리기 **직전에 한 번** 끼어드는 디스패처.
 *
 * 저장 대상 계산은 io 로 넘어가 있어, 대화상자가 닫힌 뒤 계산이 시작되기 전에 화면 선택이 바뀔 수 있다.
 * 실제로는 사용자의 클릭 타이밍에 달렸지만, 그 순간을 여기서 고정해 결정적으로 재현한다.
 */
private class HookedDispatcher : CoroutineDispatcher() {

    /** 다음 한 번의 작업 직전에 실행할 끼어들기. 한 번 쓰면 비워진다. */
    var onNextDispatch: (() -> Unit)? = null

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        onNextDispatch?.let { hook ->
            onNextDispatch = null
            hook()
        }
        block.run()
    }
}

/** 커밋당 파일 저장이 파일 **여러 개**를 쓰는 상황 — 중간 실패의 복구를 볼 수 있는 최소 구성이다. */
private fun perCommitExport(): PatchExport = PatchExport(
    perCommit = listOf(
        CommitPatch(commitId(1), ENGLISH_FORMATTED_PATCH),
        CommitPatch(commitId(2), COMBINED_PATCH),
    ),
    combined = COMBINED_PATCH,
)

private fun state(
    actions: FakePatchActions,
    files: FakePatchFiles = FakePatchFiles(),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ioDispatcher: CoroutineDispatcher = dispatcher,
): PatchState = PatchState(actions, files, CoroutineScope(dispatcher), ioDispatcher)

/** 패치를 하나 고른 뒤의 상태. 적용 흐름 검증의 공통 출발점이다. */
private fun loaded(
    actions: FakePatchActions,
    patch: ByteArray = COMBINED_PATCH,
): PatchState {
    val target = path("/tmp/fix.patch")
    val state = state(actions, FakePatchFiles(openChoice = target, contents = mapOf(target to patch)))
    state.choosePatchFile()
    return state
}
