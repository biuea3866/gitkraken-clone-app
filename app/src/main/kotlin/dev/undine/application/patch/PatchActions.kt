package dev.undine.application.patch

import dev.undine.domain.Commit
import dev.undine.domain.DiffResult
import dev.undine.domain.HistoryGateway
import dev.undine.domain.RefName
import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.CommitRange
import dev.undine.domain.patch.PatchExport
import dev.undine.domain.patch.PatchGateway
import dev.undine.domain.patch.WorkingTreeScope

/** Patch 화면이 한 페이지에 보여 줄 최근 커밋 수. 무한 스크롤·검색은 이 화면의 범위가 아니다. */
const val PATCH_COMMIT_PAGE_SIZE: Int = 100

/**
 * Patch 화면이 부르는 application 경계.
 *
 * presentation 은 이 인터페이스만 알고 [PatchGateway]·JGit 을 직접 알지 않는다 (레이어 규칙 3).
 * 생성·적용의 규칙은 이미 게이트웨이 계약이 갖고 있으므로 여기서는 화면이 필요로 하는 조회
 * (최근 커밋 한 페이지)와 미리보기 변환만 더한다 — 새 결과 타입이나 예외를 만들지 않는다.
 */
interface PatchActions {

    /** HEAD 기준 최근 커밋 한 페이지. 커밋 범위 선택 목록의 재료다. */
    suspend fun loadRecentCommits(limit: Int = PATCH_COMMIT_PAGE_SIZE): List<Commit>

    /** 현재 변경을 패치로 내보낸다. 저장소를 바꾸지 않는다. */
    suspend fun exportWorkingTree(scope: WorkingTreeScope): PatchExport

    /** 커밋 범위를 패치로 내보낸다. 저장소를 바꾸지 않는다. */
    suspend fun exportCommits(range: CommitRange): PatchExport

    /** 승격 없이 격리 적용해 본다. 결과는 [apply] 와 같은 [ApplyOutcome] 계약이다. */
    suspend fun dryRun(patch: ByteArray, mode: ApplyMode): ApplyOutcome

    suspend fun apply(patch: ByteArray, mode: ApplyMode): ApplyOutcome

    /** 적용 전에 읽을 diff. 미리볼 수 없으면 사유를 담아 돌려주며 적용을 막지는 않는다. */
    fun preview(patch: ByteArray): DiffResult

    /** 커밋 모드에서 "패치에서 가져옴" 으로 보여 줄 작성자·메시지. */
    fun commitMetadataOf(patch: ByteArray): PatchCommitMetadata
}

/**
 * 목록에서 고른 **연속 구간**을 [CommitRange] 로 옮긴다. 고른 것이 없으면 `null` 이라 화면이
 * 생성 버튼을 막는다.
 *
 * - `to` = 고른 것 중 가장 최신 커밋 (목록 순서상 앞)
 * - `from` = 가장 오래된 커밋의 **첫 부모**. 부모가 없으면(최초 커밋) `null` — [CommitRange] 가
 *   "루트부터" 로 정의한 값이다.
 *
 * **첫 부모를 쓴다.** 구간 끝이 병합 커밋이면 부모가 둘 이상인데, 두 번째 부모를 기준으로 잡으면
 * 사용자가 고르지 않은 갈래가 통째로 패치에 들어온다. 목록에 보이는 순서와 같은 선을 쓴다.
 *
 * @param selected 목록 순서(최신 → 과거)를 그대로 유지한 선택 구간.
 */
fun commitRangeOf(selected: List<Commit>): CommitRange? {
    val newest = selected.firstOrNull() ?: return null
    val oldest = selected.last()
    return CommitRange(from = oldest.parents.firstOrNull(), to = newest.id)
}

/**
 * [PatchActions] 구현.
 *
 * I/O 디스패치와 JGit `Repository` 직렬화는 게이트웨이 구현 안의 `GitAccess` 가 맡으므로 여기서
 * `withContext` 를 다시 걸지 않는다. 취소는 그대로 전파된다.
 */
class PatchActionService(
    private val patchGateway: PatchGateway,
    private val historyGateway: HistoryGateway,
) : PatchActions {

    override suspend fun loadRecentCommits(limit: Int): List<Commit> =
        historyGateway.load(refs = listOf(RefName(HEAD_REF)), offset = 0, limit = limit)

    override suspend fun exportWorkingTree(scope: WorkingTreeScope): PatchExport = patchGateway.export(scope)

    override suspend fun exportCommits(range: CommitRange): PatchExport = patchGateway.export(range)

    override suspend fun dryRun(patch: ByteArray, mode: ApplyMode): ApplyOutcome = patchGateway.dryRun(patch, mode)

    override suspend fun apply(patch: ByteArray, mode: ApplyMode): ApplyOutcome = patchGateway.apply(patch, mode)

    override fun preview(patch: ByteArray): DiffResult = previewPatch(patch)

    override fun commitMetadataOf(patch: ByteArray): PatchCommitMetadata = patchCommitMetadataOf(patch)
}

private const val HEAD_REF = "HEAD"
