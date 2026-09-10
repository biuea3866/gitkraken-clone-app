package dev.undine.presentation.toolbar

import androidx.compose.runtime.Immutable
import dev.undine.domain.Branch
import dev.undine.domain.RefName

/** 원격 추적 브랜치 참조의 접두사. `refs/remotes/origin/main` 의 `refs/remotes/` 부분이다. */
internal const val REMOTE_REF_PREFIX = "refs/remotes/"

/** 로컬 브랜치 참조의 사용자 표기 접두사. 문장에 `refs/heads/` 를 그대로 보여주지 않는다. */
internal const val LOCAL_BRANCH_PREFIX = "refs/heads/"

/** 사용자에게 보여줄 브랜치 이름. `refs/heads/main` 을 `main` 으로 줄인다. */
internal fun RefName.displayName(): String = value.removePrefix(LOCAL_BRANCH_PREFIX)

/** 브랜치를 지목해 할 수 있는 원격 조작. */
enum class BranchRemoteOperation {
    PULL,
    PUSH,
}

/**
 * 지목 조작이 지금 불가능한 사유. **막혀도 항목을 숨기지 않고 이 사유를 붙인다** — 사라지면
 * 사용자는 그 조작이 없는 브랜치라고 읽는다 (결정 D5).
 */
enum class BranchRemoteRefusal {
    /** 저장소에 등록된 원격이 없다. */
    NO_REMOTE,

    /** 그 브랜치에 추적 원격이 없어 대상을 정할 수 없다. */
    NO_UPSTREAM,

    /**
     * 업스트림이 없는데 **원격이 여럿**이라 어디로 올릴지 정할 수 없다.
     *
     * `RemoteGateway.push` 는 **미는 ref 의 업스트림**에서 원격을 정하고, 없으면 원격이 하나일 때만
     * 그것을 쓴다. 둘 이상이면 짐작해서 고르지 않고 거부한다 — 의도하지 않은 곳에 올라가면
     * 되돌릴 방법이 마땅치 않다.
     */
    AMBIGUOUS_REMOTE,

    /**
     * 현재 체크아웃된 브랜치는 지목 받기 대상이 아니다 (결정 D8).
     *
     * 포인터만 옮기면 워킹트리가 HEAD 와 어긋난 채 남고, 워킹트리까지 맞추는 대안은 커밋하지 않은
     * 편집을 지운다 — 받기가 사용자의 편집을 지우는 연산이 될 수 없다. 툴바의 받기로 안내한다.
     */
    CURRENT_BRANCH,
}

/**
 * 메뉴 항목 하나 — 조작과, 지금 실행할 수 없다면 그 사유.
 *
 * [busy] 는 다른 원격 작업이 도는 중이라는 뜻이라 **사유가 아니다** — 곧 풀리는 상태를 구조적
 * 거부와 같은 문장으로 말하지 않는다. 툴바 버튼이 실행 중 전부 잠기는 것과 같은 규칙이다.
 */
@Immutable
data class BranchRemoteEntry(
    val operation: BranchRemoteOperation,
    val blockedReason: BranchRemoteRefusal?,
    val busy: Boolean,
) {
    val enabled: Boolean get() = blockedReason == null && !busy
}

/**
 * 지목 조작이 막힌 사유 — `null` 이면 구조적으로는 실행할 수 있다.
 *
 * **판정은 여기 하나뿐이다.** 진입점(우클릭·`⋯`)마다 조건을 다시 쓰면 그것이 두 번째 판정이 되고,
 * 한 곳을 고쳤을 때 나머지가 조용히 남는다 (결정 D4, UND-94 D17).
 *
 */
fun branchRemoteRefusalOf(
    branch: Branch,
    operation: BranchRemoteOperation,
    remotes: List<String>,
): BranchRemoteRefusal? {
    if (remotes.isEmpty()) return BranchRemoteRefusal.NO_REMOTE
    val trackedRemote = trackedRemoteOf(branch, remotes)
    return when (operation) {
        // 현재 브랜치 여부를 업스트림보다 먼저 본다 — 툴바를 쓰라는 안내가 더 실행 가능한 다음 행동이다.
        BranchRemoteOperation.PULL -> when {
            branch.isCurrent -> BranchRemoteRefusal.CURRENT_BRANCH
            trackedRemote == null -> BranchRemoteRefusal.NO_UPSTREAM
            else -> null
        }

        // push 는 미는 ref 의 업스트림으로 간다 — 현재 브랜치가 무엇이든 상관없다.
        // 업스트림이 없어도 원격이 하나면 모호하지 않다 (원격에 아직 없는 새 브랜치가 그 경우다).
        BranchRemoteOperation.PUSH ->
            if (pushRemoteOf(branch, remotes) != null) null else BranchRemoteRefusal.AMBIGUOUS_REMOTE
    }
}

/**
 * 그 브랜치가 추적하는 원격 이름. 업스트림이 없거나 주어진 목록에 없는 원격을 가리키면 `null` 이다.
 *
 * 원격 이름에 `/` 가 들어갈 수 있어(`team/fork`) 첫 조각을 자르지 않고 **목록과 대조**한다 —
 * 가장 긴 접두사가 실제 원격이다. 전체 이름(`refs/remotes/...`)으로 들어와도 같은 규칙으로 본다.
 */
/**
 * 그 브랜치를 **올릴 때 실제로 향하는 원격**.
 *
 * `RemoteGatewayImpl` 의 규칙과 같다: 미는 ref 의 업스트림, 없으면 원격이 하나일 때 그것.
 * 가드([branchRemoteRefusalOf])와 실행([RemoteToolbarState.pushBranch])이 **이 함수 하나**를 본다 —
 * 각자 판정하면 가드는 열어 두고 실행은 조용히 아무것도 안 하는 상태가 된다.
 */
fun pushRemoteOf(branch: Branch, remotes: List<String>): String? =
    trackedRemoteOf(branch, remotes) ?: remotes.singleOrNull()

fun trackedRemoteOf(branch: Branch, remotes: List<String>): String? =
    branch.upstream?.let { upstream -> remoteNameOf(upstream, remotes) }

internal fun remoteNameOf(upstream: RefName, remotes: List<String>): String? {
    val tracking = upstream.value.removePrefix(REMOTE_REF_PREFIX)
    return remotes.filter { tracking.startsWith("$it/") }.maxByOrNull(String::length)
}
