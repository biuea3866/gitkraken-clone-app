package dev.undine.domain.worktree

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val MAIN_NAME = "repo"
private const val LINKED_NAME = "feature-a"
private const val GONE_NAME = "gone"

/**
 * worktree **도메인 계약** — 저장소 없이 검증되는 부분만 본다
 * (`testing` 규칙 3: 순수 로직은 저장소 없이 빠르고 결정적으로).
 *
 * JGit 을 실제로 만지는 동작은 [dev.undine.infrastructure.git.worktree.WorktreeGatewayImplSpec] 이
 * 실제 임시 저장소로 검증한다.
 */
class WorktreeContractSpec : FunSpec({

    test("조회 결과는 메인 worktree 를 연결·고아와 구분해 담는다") {
        val listing = WorktreeListing(
            worktrees = listOf(
                worktree(MAIN_NAME, WorktreeState.MAIN),
                worktree(LINKED_NAME, WorktreeState.LINKED),
                worktree(GONE_NAME, WorktreeState.ORPHANED),
            ),
            unsupported = emptyList(),
        )

        listing.main?.name shouldBe MAIN_NAME
        listing.worktrees.filter { it.state == WorktreeState.ORPHANED }
            .map { it.name } shouldContainExactly listOf(GONE_NAME)
    }

    test("메인 worktree 가 없는 목록의 main 은 null 이다 — 첫 항목으로 대신하지 않는다") {
        val listing = WorktreeListing(
            worktrees = listOf(worktree(LINKED_NAME, WorktreeState.LINKED)),
            unsupported = emptyList(),
        )

        listing.main.shouldBeNull()
    }

    test("detached HEAD worktree 의 브랜치는 null 이다 — 빈 문자열로 뭉개지 않는다") {
        worktree(LINKED_NAME, WorktreeState.LINKED, branch = null).branch.shouldBeNull()
    }

    test("읽을 수 없는 등록은 목록에서 사라지지 않고 미지원 항목으로 함께 보고된다") {
        val listing = WorktreeListing(
            worktrees = listOf(worktree(MAIN_NAME, WorktreeState.MAIN)),
            unsupported = listOf(UnsupportedWorktreeMetadata("broken", "HEAD 를 읽을 수 없습니다")),
        )

        // 성공 목록만 보면 정상으로 보이지만, 미지원 항목이 남아 있어 화면이 사실대로 알릴 수 있다.
        listing.unsupported.map { it.name } shouldContainExactly listOf("broken")
    }

    test("worktree 상태는 메인·연결·고아 세 값으로 닫혀 있다") {
        WorktreeState.entries.map { it.name } shouldContainExactly listOf("MAIN", "LINKED", "ORPHANED")
    }

    test("계약은 조회·생성·제거 셋뿐이다 — move·prune·lock 을 노출하지 않는다") {
        WorktreeGateway::class.java.declaredMethods.map { it.name.substringBefore('-') }
            .shouldContainExactlyInAnyOrder(listOf("list", "add", "remove"))
    }

    test("제거에는 강제 인자가 없다 — 더티 worktree 는 항상 거부된다") {
        val remove = WorktreeGateway::class.java.declaredMethods.first { it.name == "remove" }

        // suspend 함수의 JVM 시그니처는 name + continuation 뿐이다. force 를 받을 자리가 없다.
        remove.parameterTypes.map { it.simpleName } shouldContainExactly listOf("String", "Continuation")
    }

    test("중복 체크아웃·메인 제거 거부는 상태 위반으로, 더티는 미커밋 변경으로 구분된다") {
        val duplicate = UndineException.StateViolation("브랜치 '$LINKED_NAME' 은 이미 체크아웃돼 있습니다")
        val dirty = UndineException.DirtyWorkingTree(listOf("code.txt"))

        // 화면이 취할 행동이 다르다 — 상태 위반은 왜 불가능한지 설명하고, 더티는 무엇을 정리할지 알린다.
        duplicate.detail shouldContain LINKED_NAME
        dirty.paths shouldContainExactly listOf("code.txt")
    }

    test("없는 브랜치는 참조 부재로, 없는 worktree 는 워크트리 부재로 구분된다") {
        UndineException.NotFound(UndineException.NotFound.Kind.REF, LINKED_NAME)
            .message shouldBe "참조 을(를) 찾을 수 없습니다: '$LINKED_NAME'"
        UndineException.NotFound(UndineException.NotFound.Kind.WORKTREE, LINKED_NAME)
            .message shouldBe "워크트리 을(를) 찾을 수 없습니다: '$LINKED_NAME'"
    }
})

private fun worktree(
    name: String,
    state: WorktreeState,
    branch: RefName? = RefName("refs/heads/$name"),
): Worktree = Worktree(
    name = name,
    path = RepositoryPath("/tmp/$name"),
    branch = branch,
    state = state,
)
