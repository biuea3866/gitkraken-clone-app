package dev.undine.domain.undo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * 기록을 남기는 production 경로. 값은 그 경로를 사람이 알아볼 수 있는 이름으로 적는다.
 *
 * **이 표가 이 스펙의 요점이다.** `GitOperationKind` 에 값만 있고 아무도 기록하지 않으면, 사용자는
 * 모든 동작이 되돌려진다고 믿다가 되돌리려는 바로 그 순간에 그 믿음이 깨진다 (UND-79).
 */
private val PRODUCERS: Map<GitOperationKind, String> = mapOf(
    GitOperationKind.COMMIT to "CommitStagedUseCase · AmendCommitUseCase",
    GitOperationKind.CHECKOUT to "CheckoutBranchUseCase",
    GitOperationKind.MERGE to "MergeBranchUseCase · ContinueAfterResolveUseCase · ExecuteGraphOperationUseCase",
    GitOperationKind.REBASE to
        "RebaseBranchUseCase · ContinueAfterResolveUseCase · ApplyRebasePlanUseCase · ExecuteGraphOperationUseCase",
    GitOperationKind.CHERRY_PICK to
        "CherryPickCommitsUseCase · ContinueCherryPickUseCase · ExecuteGraphOperationUseCase",
    GitOperationKind.PUSH to "PushRemoteUseCase (되돌릴 수 없다는 사유와 함께)",
    GitOperationKind.BRANCH_MOVE to
        "ExecuteGraphOperationUseCase (GraphOperation.ResetBranch — hard reset 으로 실행한다)",
    GitOperationKind.TAG_MOVE to "ExecuteGraphOperationUseCase (태그 드롭)",
    GitOperationKind.SUBMODULE_INIT to "InitializeSubmoduleUseCase",
    GitOperationKind.SUBMODULE_UPDATE to "UpdateSubmoduleUseCase",
    GitOperationKind.WORKTREE_ADD to "AddWorktreeUseCase",
    GitOperationKind.WORKTREE_REMOVE to "RemoveWorktreeUseCase",
    GitOperationKind.REFLOG_RESTORE to "RecoveryActionService.recover",
    GitOperationKind.BISECT_SESSION to "RecoveryActionService 의 bisect 시작·판정·reset",
)

/**
 * 기록 producer 가 **없는 것이 맞는** 값과 그 사유.
 *
 * 여기 있다는 것은 "빠뜨렸다" 가 아니라 **그 기능으로 가는 화면 경로가 없다** 는 뜻이다. 기록만
 * 만들려고 기능을 신설하지 않는다 (UND-79 AC 9).
 */
private val NO_PRODUCERS: Map<GitOperationKind, String> = mapOf(
    GitOperationKind.BRANCH_CREATE to "브랜치를 만드는 UseCase 가 없다 — 사이드바는 조회·체크아웃·이름변경·삭제만 한다.",
    GitOperationKind.STASH_PUSH to "stash 를 만드는 UseCase 가 없다 — 사이드바는 stash 목록만 읽는다.",
    GitOperationKind.STASH_DROP to
        "stash 를 지우는 UseCase 가 없다. UndoService 내부의 삭제는 되돌리기 실행이지 producer 가 아니다.",
    GitOperationKind.HARD_RESET to
        "전용 hard reset 진입점이 화면에 없다 — 그래프의 ResetBranch 는 BRANCH_MOVE 로 기록되고" +
            " (UND-42·결정 G31), 그 표시값이 hard reset 임을 말한다(UND-84). 기록을 이중으로 남기지 않는다.",
)

/** production 소스에서 이 값을 기록에 쓰는지 대조할 범위. 테스트 코드는 producer 가 아니다. */
private val MAIN_SOURCES: List<String> by lazy {
    mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
}

/**
 * `app/src/main/kotlin` 을 찾는다. 실행 디렉토리가 레포 루트든 모듈이든 같은 곳을 가리키게
 * **위로 올라가며** 찾는다 — 경로를 한 형태로 박으면 실행 방식이 바뀔 때 조용히 빈 목록이 된다.
 */
private fun mainSourceRoot(): File {
    var current: File? = File("").absoluteFile
    while (current != null) {
        listOf("app/src/main/kotlin", "src/main/kotlin")
            .map { File(current, it) }
            .firstOrNull { it.isDirectory }
            ?.let { return it }
        current = current.parentFile
    }
    error("main 소스 루트를 찾지 못했습니다 (실행 위치: ${File("").absolutePath})")
}

class GitOperationKindProducerSpec : FunSpec({

    test("모든 GitOperationKind 값이 producer 표 또는 부재 사유 표 중 정확히 한쪽에 있다") {
        // 새 값을 추가하고 어느 표에도 적지 않으면 여기서 멈춘다 — 조용히 기록되지 않는 연산이 생기지 않는다.
        (PRODUCERS.keys + NO_PRODUCERS.keys) shouldContainExactlyInAnyOrder GitOperationKind.entries

        (PRODUCERS.keys intersect NO_PRODUCERS.keys).shouldBeEmpty()
    }

    test("부재 사유는 BRANCH_CREATE·STASH_PUSH·STASH_DROP 을 포함하고 모두 사유가 비어 있지 않다") {
        NO_PRODUCERS.keys shouldContainExactlyInAnyOrder listOf(
            GitOperationKind.BRANCH_CREATE,
            GitOperationKind.STASH_PUSH,
            GitOperationKind.STASH_DROP,
            GitOperationKind.HARD_RESET,
        )
        NO_PRODUCERS.values.filter { it.isBlank() }.shouldBeEmpty()
        PRODUCERS.values.filter { it.isBlank() }.shouldBeEmpty()
    }

    test("producer 로 적은 값은 실제로 production 소스가 참조한다") {
        // 표만 보면 producer 를 지워도 통과한다 — 소스와 대조해야 표가 사실을 말한다.
        val unreferenced = PRODUCERS.keys.filterNot { kind ->
            MAIN_SOURCES.any { source -> source.contains("GitOperationKind.${kind.name}") }
        }
        unreferenced.shouldBeEmpty()
    }

    test("producer 가 없다고 적은 값은 production 소스가 참조하지 않는다") {
        val referenced = NO_PRODUCERS.keys.filter { kind ->
            MAIN_SOURCES.any { source -> source.contains("GitOperationKind.${kind.name}") }
        }
        referenced.shouldBeEmpty()
    }

    test("워킹트리를 덮어쓰는 기록은 표시값이 그 사실을 말한다") {
        // 그래프의 브랜치 드롭은 hard reset 이라 워킹트리 변경이 사라진다 (UND-84). 되돌릴 수 있는지와
        // 무엇을 잃었는지는 다른 질문이고, "브랜치 이동" 만으로는 뒤쪽이 감춰진다.
        val branchMove = GitOperationKind.BRANCH_MOVE.label
        branchMove shouldContain "hard reset"
        branchMove shouldContain "워킹트리"
        branchMove shouldContain "유실"
    }

    test("파괴적이지 않은 태그 이동은 파괴적으로 표시하지 않는다") {
        val tagMove = GitOperationKind.TAG_MOVE.label
        tagMove shouldNotContain "hard reset"
        tagMove shouldNotContain "워킹트리"
    }

    test("모든 값에 사람이 읽는 이름이 있다") {
        GitOperationKind.entries.filter { it.label.isBlank() }.shouldBeEmpty()
        GitOperationKind.entries.size shouldBe PRODUCERS.size + NO_PRODUCERS.size
    }
})
