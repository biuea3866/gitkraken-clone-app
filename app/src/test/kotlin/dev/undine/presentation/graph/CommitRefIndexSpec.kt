package dev.undine.presentation.graph

import dev.undine.domain.Branch
import dev.undine.domain.RefName
import dev.undine.domain.Tag
import dev.undine.domain.graphops.GraphDragSource
import dev.undine.domain.graphops.GraphDropProposal
import dev.undine.domain.graphops.GraphDropRefusal
import dev.undine.domain.graphops.GraphDropTarget
import dev.undine.domain.graphops.proposeGraphDrop
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")

private fun branch(name: RefName, target: Int, isCurrent: Boolean = false) = Branch(
    name = name,
    target = commitId(target),
    isCurrent = isCurrent,
    isRemote = false,
    upstream = null,
    ahead = 0,
    behind = 0,
)

private fun tag(name: String, target: Int, isAnnotated: Boolean = false) = Tag(
    name = RefName(name),
    target = commitId(target),
    isAnnotated = isAnnotated,
    message = if (isAnnotated) "release" else null,
    tagger = null,
)

/** 커밋 → ref 칩 색인. 어떤 칩이 어느 커밋에 붙는지가 이 타입의 책임이다. */
class CommitRefIndexSpec : FunSpec({

    test("브랜치와 태그는 각자 가리키는 커밋 행에 칩으로 붙는다") {
        val index = CommitRefIndex.of(
            branches = listOf(branch(MAIN, 1), branch(FEATURE, 2)),
            tags = listOf(tag("v1.0.0", 2)),
            currentBranch = null,
        )

        index.chipsFor(commitId(1)) shouldContainExactly listOf(
            GraphRefChip("main", GraphRefKind.BRANCH, target = commitId(1)),
        )
        index.chipsFor(commitId(2)) shouldContainExactly listOf(
            GraphRefChip("feature", GraphRefKind.BRANCH, target = commitId(2)),
            GraphRefChip("v1.0.0", GraphRefKind.TAG, target = commitId(2)),
        )
    }

    test("현재 브랜치가 있으면 그 브랜치가 가리키는 커밋에 HEAD 칩이 먼저 붙는다") {
        val index = CommitRefIndex.of(
            branches = listOf(branch(MAIN, 1, isCurrent = true)),
            tags = emptyList(),
            currentBranch = MAIN,
        )

        index.chipsFor(commitId(1)) shouldContainExactly listOf(
            GraphRefChip(refName = null, kind = GraphRefKind.HEAD, target = commitId(1)),
            GraphRefChip("main", GraphRefKind.BRANCH, target = commitId(1)),
        )
    }

    test("detached HEAD 면 HEAD 칩을 그리지 않는다") {
        val index = CommitRefIndex.of(
            branches = listOf(branch(MAIN, 1)),
            tags = emptyList(),
            currentBranch = null,
        )

        index.chipsFor(commitId(1)).map { it.kind } shouldContainExactly listOf(GraphRefKind.BRANCH)
    }

    test("ref 가 없는 커밋과 빈 색인은 칩을 만들지 않는다") {
        val index = CommitRefIndex.of(
            branches = listOf(branch(MAIN, 1)),
            tags = emptyList(),
            currentBranch = MAIN,
        )

        index.chipsFor(commitId(9)).shouldBeEmpty()
        CommitRefIndex.EMPTY.chipsFor(commitId(1)).shouldBeEmpty()
    }

    test("annotated 태그는 그 사실을 칩에 실어 옮길 수 없는 source 로 만든다") {
        val index = CommitRefIndex.of(
            branches = emptyList(),
            tags = listOf(tag("v1.0.0", 2, isAnnotated = true), tag("nightly", 2)),
            currentBranch = null,
        )

        index.chipsFor(commitId(2)) shouldContainExactly listOf(
            GraphRefChip("v1.0.0", GraphRefKind.TAG, target = commitId(2), isAnnotated = true),
            GraphRefChip("nightly", GraphRefKind.TAG, target = commitId(2), isAnnotated = false),
        )

        // 칩이 실어 준 값이 그대로 드롭 불가 판정까지 이어져야 한다 — 여기서 끊기면 화면은
        // annotated 태그를 옮길 수 있는 것처럼 보여 준다.
        val annotated = index.chipsFor(commitId(2)).first { it.refName == "v1.0.0" }
        proposeGraphDrop(
            GraphDragSource.Tag(
                RefName(requireNotNull(annotated.refName)),
                requireNotNull(annotated.target),
                annotated.isAnnotated,
            ),
            GraphDropTarget.Commit(commitId(1)),
        ) shouldBe GraphDropProposal.Unavailable(GraphDropRefusal.ANNOTATED_TAG)
    }

    test("현재 브랜치 이름이 브랜치 목록에 없으면 HEAD 칩을 그리지 않는다") {
        val index = CommitRefIndex.of(
            branches = listOf(branch(MAIN, 1)),
            tags = emptyList(),
            currentBranch = FEATURE,
        )

        index.chipsFor(commitId(1)).any { it.kind == GraphRefKind.HEAD } shouldBe false
    }
})
