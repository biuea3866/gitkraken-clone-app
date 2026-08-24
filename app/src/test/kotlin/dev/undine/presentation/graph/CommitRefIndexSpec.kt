package dev.undine.presentation.graph

import dev.undine.domain.Branch
import dev.undine.domain.RefName
import dev.undine.domain.Tag
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

private fun tag(name: String, target: Int) = Tag(
    name = RefName(name),
    target = commitId(target),
    isAnnotated = false,
    message = null,
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
            GraphRefChip("main", GraphRefKind.BRANCH),
        )
        index.chipsFor(commitId(2)) shouldContainExactly listOf(
            GraphRefChip("feature", GraphRefKind.BRANCH),
            GraphRefChip("v1.0.0", GraphRefKind.TAG),
        )
    }

    test("현재 브랜치가 있으면 그 브랜치가 가리키는 커밋에 HEAD 칩이 먼저 붙는다") {
        val index = CommitRefIndex.of(
            branches = listOf(branch(MAIN, 1, isCurrent = true)),
            tags = emptyList(),
            currentBranch = MAIN,
        )

        index.chipsFor(commitId(1)) shouldContainExactly listOf(
            GraphRefChip(refName = null, kind = GraphRefKind.HEAD),
            GraphRefChip("main", GraphRefKind.BRANCH),
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

    test("현재 브랜치 이름이 브랜치 목록에 없으면 HEAD 칩을 그리지 않는다") {
        val index = CommitRefIndex.of(
            branches = listOf(branch(MAIN, 1)),
            tags = emptyList(),
            currentBranch = FEATURE,
        )

        index.chipsFor(commitId(1)).any { it.kind == GraphRefKind.HEAD } shouldBe false
    }
})
