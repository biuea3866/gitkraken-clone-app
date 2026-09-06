package dev.undine.presentation.patch

import dev.undine.application.patch.PatchActions
import dev.undine.application.patch.PatchCommitMetadata
import dev.undine.application.patch.patchCommitMetadataOf
import dev.undine.application.patch.previewPatch
import dev.undine.domain.Commit
import dev.undine.domain.DiffResult
import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.CommitRange
import dev.undine.domain.patch.PatchExport
import dev.undine.domain.patch.WorkingTreeScope
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred

/**
 * 화면이 부르는 application 경계의 대역.
 *
 * 미리보기·메타데이터는 실제 구현을 그대로 호출한다 — 화면이 보는 값이 실제 파서가 낸 값과
 * 달라지면 검증이 대역의 거짓말을 확인하는 꼴이 된다.
 */
@Suppress("LongParameterList") // 대역이 흉내 낼 결과 갈래만큼 늘어난다 — 케이스마다 named argument 로 고른다.
class FakePatchActions(
    private val commits: List<Commit> = emptyList(),
    private val commitsFailure: UndineException? = null,
    private val exportResult: PatchExport = PatchExport(emptyList(), ByteArray(0)),
    private val exportFailure: UndineException? = null,
    private val dryRunOutcome: ApplyOutcome = ApplyOutcome.Applied(listOf("src/Main.kt")),
    private val dryRunFailure: Throwable? = null,
    /** 호출 순서대로 돌려줄 dry-run 결과. 비어 있으면 매번 [dryRunOutcome] 이다. */
    private val dryRunOutcomes: List<ApplyOutcome> = emptyList(),
    private val applyOutcome: ApplyOutcome = ApplyOutcome.Applied(listOf("src/Main.kt")),
    private val applyFailure: Throwable? = null,
    private val exportGate: CompletableDeferred<Unit>? = null,
    /** **첫** dry-run 만 붙잡아 둔다 — 늦게 끝난 앞선 검사가 새 패치를 덮는지 보려면 필요하다. */
    private val dryRunGate: CompletableDeferred<Unit>? = null,
    /** **첫** 적용만 붙잡아 둔다 — 늦게 끝난 앞선 적용이 새 패치의 결과인 척 앉는지 보려면 필요하다. */
    private val applyGate: CompletableDeferred<Unit>? = null,
) : PatchActions {

    /** 최근 커밋을 몇 번 읽었는가. 홀더가 새로 만들어지면 배선이 다시 읽으므로 1보다 커진다. */
    var commitLoads: Int = 0
        private set

    val exportedScopes = mutableListOf<WorkingTreeScope>()
    val exportedRanges = mutableListOf<CommitRange>()
    val appliedModes = mutableListOf<ApplyMode>()
    val dryRunModes = mutableListOf<ApplyMode>()

    override suspend fun loadRecentCommits(limit: Int): List<Commit> {
        commitLoads += 1
        commitsFailure?.let { throw it }
        return commits.take(limit)
    }

    override suspend fun exportWorkingTree(scope: WorkingTreeScope): PatchExport {
        exportedScopes += scope
        exportGate?.await()
        exportFailure?.let { throw it }
        return exportResult
    }

    override suspend fun exportCommits(range: CommitRange): PatchExport {
        exportedRanges += range
        exportGate?.await()
        exportFailure?.let { throw it }
        return exportResult
    }

    override suspend fun dryRun(patch: ByteArray, mode: ApplyMode): ApplyOutcome {
        dryRunModes += mode
        val call = dryRunModes.size
        if (call == 1) dryRunGate?.await()
        dryRunFailure?.let { throw it }
        return dryRunOutcomes.getOrNull(call - 1) ?: dryRunOutcome
    }

    override suspend fun apply(patch: ByteArray, mode: ApplyMode): ApplyOutcome {
        appliedModes += mode
        if (appliedModes.size == 1) applyGate?.await()
        applyFailure?.let { throw it }
        return applyOutcome
    }

    override fun preview(patch: ByteArray): DiffResult = previewPatch(patch)

    override fun commitMetadataOf(patch: ByteArray): PatchCommitMetadata = patchCommitMetadataOf(patch)
}

/**
 * 파일 대화상자·읽기·쓰기의 대역.
 *
 * 화면 테스트가 실제 AWT 대화상자를 열면 사람 조작을 기다리며 CI 가 멈추고, 실제 파일까지 쓴다.
 */
@Suppress("LongParameterList") // 대화상자 셋과 읽기·쓰기 결과를 케이스마다 named argument 로 고른다.
class FakePatchFiles(
    private val openChoice: Path? = null,
    private val saveChoice: Path? = null,
    private val directoryChoice: Path? = null,
    contents: Map<Path, ByteArray> = emptyMap(),
    private val readFailure: IOException? = null,
    private val writeFailure: IOException? = null,
    /** 몇 번째 쓰기에서 실패시킬 것인가(1부터). `null` 이면 모든 쓰기가 실패한다. */
    private val failWriteAt: Int? = null,
    private val deleteFailure: IOException? = null,
    existing: Set<Path> = emptySet(),
    /** 읽기·쓰기가 **도는 동안**의 화면 상태를 들여다보는 자리 — 진행 표시를 확인하려면 필요하다. */
    private val onRead: (() -> Unit)? = null,
    private val onWrite: (() -> Unit)? = null,
) : PatchFiles {

    /** 대역이 흉내 내는 작은 파일시스템 — 되돌리기가 실제로 내용을 복구했는지 확인하려면 필요하다. */
    private val stored: MutableMap<Path, ByteArray> =
        (contents + existing.associateWith { ByteArray(0) }).toMutableMap()

    val written = mutableListOf<Pair<Path, ByteArray>>()
    val saveDefaults = mutableListOf<String>()
    var directoryRequests: Int = 0
        private set

    private var writes: Int = 0

    override fun chooseOpenFile(): Path? = openChoice

    override fun chooseSaveFile(defaultName: String): Path? {
        saveDefaults += defaultName
        return saveChoice
    }

    override fun chooseDirectory(): Path? {
        directoryRequests += 1
        return directoryChoice
    }

    override fun read(path: Path): ByteArray {
        onRead?.invoke()
        readFailure?.let { throw it }
        return stored[path] ?: ByteArray(0)
    }

    override fun exists(path: Path): Boolean = path in stored

    override fun writeAtomically(path: Path, bytes: ByteArray) {
        onWrite?.invoke()
        writes += 1
        writeFailure?.let { failure -> if (failWriteAt == null || failWriteAt == writes) throw failure }
        stored[path] = bytes
        written += path to bytes
    }

    override fun delete(path: Path) {
        deleteFailure?.let { throw it }
        stored.remove(path)
    }

    /** 저장이 끝난 뒤 파일이 실제로 어떤 내용인가. 없으면 `null`. */
    fun contentOf(path: Path): ByteArray? = stored[path]
}

fun path(value: String): Path = Paths.get(value)
