package dev.undine.presentation.sidebar

import dev.undine.application.sidebar.CheckoutBranchUseCase
import dev.undine.application.sidebar.DeleteBranchUseCase
import dev.undine.application.sidebar.LoadSidebarRefsUseCase
import dev.undine.application.sidebar.RenameBranchUseCase
import dev.undine.application.sidebar.SidebarRefs
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.StashEntry
import dev.undine.domain.Tag
import dev.undine.domain.WorktreeOpsGateway
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/** 커밋 해시 자리를 채우는 고정값 — 시간·난수에 의존하지 않는다 (testing 규칙 7). */
internal fun commitId(prefix: String): CommitId = CommitId.of(prefix.padEnd(40, '0'))

internal fun branchOf(
    name: String,
    isCurrent: Boolean = false,
    isRemote: Boolean = false,
    ahead: Int = 0,
    behind: Int = 0,
): Branch = Branch(
    name = RefName(name),
    target = commitId(name.filter { it.isDigit() }.ifEmpty { "a" }),
    isCurrent = isCurrent,
    isRemote = isRemote,
    upstream = if (isRemote) null else RefName("origin/$name"),
    ahead = ahead,
    behind = behind,
)

internal fun tagOf(name: String): Tag = Tag(
    name = RefName(name),
    target = commitId("b"),
    isAnnotated = false,
    message = null,
    tagger = null,
)

internal fun stashOf(index: Int, message: String, includedUntracked: Boolean = false): StashEntry = StashEntry(
    index = index,
    message = message,
    target = commitId("c$index"),
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    includedUntracked = includedUntracked,
)

/** [sampleRefs] 가 담는 브랜치. 태그·메뉴를 집을 때 같은 값을 다시 적지 않도록 이름을 붙여 둔다. */
internal val SAMPLE_MAIN = branchOf("main", isCurrent = true, ahead = 2, behind = 1)
internal val SAMPLE_FEATURE = branchOf("feature/login")
internal val SAMPLE_REMOTE_MAIN = branchOf("origin/main", isRemote = true)

/** 세 그룹이 모두 채워진 기본 화면 데이터. */
internal fun sampleRefs(): SidebarRefs = SidebarRefs(
    branches = listOf(SAMPLE_MAIN, SAMPLE_FEATURE, SAMPLE_REMOTE_MAIN),
    tags = listOf(tagOf("v1.0.0")),
    stashes = listOf(stashOf(0, "작업 중", includedUntracked = true)),
)

/**
 * 실제 UseCase 를 Gateway mock 위에 얹은 상태 홀더.
 *
 * `Dispatchers.Unconfined` 스코프라 `launch` 가 호출 지점에서 그대로 실행돼
 * 코루틴 테스트 의존성 없이 결정적으로 검증된다 (wave 3 결정 A2 — 빌드 파일은 UND-12 소유).
 */
internal class SidebarStateHarness(
    val refGateway: RefGateway = mockk(relaxUnitFun = true),
    val worktreeOpsGateway: WorktreeOpsGateway = mockk(relaxUnitFun = true),
) {
    val state: SidebarState = SidebarState(
        loadRefs = LoadSidebarRefsUseCase(refGateway, worktreeOpsGateway),
        checkoutBranch = CheckoutBranchUseCase(refGateway),
        renameBranch = RenameBranchUseCase(refGateway),
        deleteBranch = DeleteBranchUseCase(refGateway),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    fun withRefs(refs: SidebarRefs = sampleRefs()): SidebarStateHarness {
        coEvery { refGateway.listBranches() } returns refs.branches
        coEvery { refGateway.listTags() } returns refs.tags
        coEvery { worktreeOpsGateway.stashList() } returns refs.stashes
        return this
    }

    /** 목록을 불러온 뒤의 상태를 돌려준다 — 대부분의 검증이 여기서 시작한다. */
    fun loaded(refs: SidebarRefs = sampleRefs()): SidebarState {
        withRefs(refs)
        state.refresh()
        return state
    }
}
