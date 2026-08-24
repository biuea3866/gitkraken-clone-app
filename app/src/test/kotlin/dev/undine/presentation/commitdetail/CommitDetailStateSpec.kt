package dev.undine.presentation.commitdetail

import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val MERGE_COMMIT = commitOf(parents = listOf(FIRST_PARENT, SECOND_PARENT))

// 다른 커밋으로 옮겼을 때를 검증하려면 해시도 달라야 한다 — 요청 열쇠가 커밋 해시를 쓴다.
private val INITIAL_COMMIT = commitOf(id = CommitId.of("4".repeat(40)), parents = emptyList())

/** 상세 패널의 상태 홀더 — 기준 부모 선택·본문 접기·조회 실패 노출. */
class CommitDetailStateSpec : FunSpec({

    test("기준 부모의 기본값은 첫 부모다") {
        val state = CommitDetailState(useCaseOf(FakeDiffGateway()))

        state.baseParentIndexOf(MERGE_COMMIT) shouldBe FIRST_PARENT_INDEX
    }

    test("기준 부모를 바꾸면 그 커밋에서만 선택이 유지된다") {
        val state = CommitDetailState(useCaseOf(FakeDiffGateway()))

        state.selectBaseParent(MERGE_COMMIT, 1)

        state.baseParentIndexOf(MERGE_COMMIT) shouldBe 1
        // 다른 커밋은 부모 수가 다르므로 선택이 넘어가면 범위를 벗어난다.
        state.baseParentIndexOf(INITIAL_COMMIT) shouldBe FIRST_PARENT_INDEX
    }

    test("부모 수 범위를 벗어난 기준 부모는 거부한다") {
        val state = CommitDetailState(useCaseOf(FakeDiffGateway()))

        shouldThrow<IllegalArgumentException> { state.selectBaseParent(MERGE_COMMIT, 2) }
        shouldThrow<IllegalArgumentException> { state.selectBaseParent(MERGE_COMMIT, -1) }
    }

    test("부모가 없는 최초 커밋도 첫 부모 인덱스를 기준으로 읽는다") {
        val gateway = FakeDiffGateway(
            filesByParentIndex = mapOf(
                FIRST_PARENT_INDEX to listOf(
                    fileChangeOf("README.md", ChangeType.ADDED, deletedLines = 0),
                    fileChangeOf("build.gradle.kts", ChangeType.ADDED, deletedLines = 0),
                ),
            ),
        )
        val state = CommitDetailState(useCaseOf(gateway))

        state.load(INITIAL_COMMIT.id, state.baseParentIndexOf(INITIAL_COMMIT))

        gateway.requestedParentIndexes shouldContainExactly listOf(FIRST_PARENT_INDEX)
        val loaded = state.changedFilesOf(INITIAL_COMMIT.id, FIRST_PARENT_INDEX)
            .shouldBeInstanceOf<ChangedFilesUiState.Loaded>()
        loaded.files.map { it.changeType } shouldContainExactly listOf(ChangeType.ADDED, ChangeType.ADDED)
    }

    test("기준 부모별로 다른 변경 파일 목록을 읽는다") {
        val gateway = FakeDiffGateway(
            filesByParentIndex = mapOf(
                0 to listOf(fileChangeOf("first.kt")),
                1 to listOf(fileChangeOf("second.kt"), fileChangeOf("third.kt")),
            ),
        )
        val state = CommitDetailState(useCaseOf(gateway))

        state.load(MERGE_COMMIT.id, 0)
        state.changedFilesOf(MERGE_COMMIT.id, 0).shouldBeInstanceOf<ChangedFilesUiState.Loaded>()
            .files.map { it.path } shouldContainExactly listOf("first.kt")

        state.load(MERGE_COMMIT.id, 1)
        state.changedFilesOf(MERGE_COMMIT.id, 1).shouldBeInstanceOf<ChangedFilesUiState.Loaded>()
            .files.map { it.path } shouldContainExactly listOf("second.kt", "third.kt")
    }

    test("변경 파일이 0건이면 빈 성공 상태가 된다") {
        val state = CommitDetailState(useCaseOf(FakeDiffGateway()))

        state.load(MERGE_COMMIT.id, FIRST_PARENT_INDEX)

        state.changedFilesOf(MERGE_COMMIT.id, FIRST_PARENT_INDEX)
            .shouldBeInstanceOf<ChangedFilesUiState.Loaded>().files shouldBe emptyList()
    }

    test("조회 실패는 빈 목록으로 숨기지 않고 실패 상태로 노출한다") {
        val gateway = FakeDiffGateway(
            failure = UndineException.GitOperationFailed("changedFiles"),
        )
        val state = CommitDetailState(useCaseOf(gateway))

        state.load(MERGE_COMMIT.id, FIRST_PARENT_INDEX)

        val failed = state.changedFilesOf(MERGE_COMMIT.id, FIRST_PARENT_INDEX)
            .shouldBeInstanceOf<ChangedFilesUiState.Failed>()
        failed.failure.shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "changedFiles"
    }

    test("커밋을 바꾸면 앞 커밋의 변경 파일 목록이 새 커밋에 남지 않는다") {
        val gateway = FakeDiffGateway(filesByParentIndex = mapOf(0 to listOf(fileChangeOf("first.kt"))))
        val state = CommitDetailState(useCaseOf(gateway))

        state.load(MERGE_COMMIT.id, FIRST_PARENT_INDEX)

        state.changedFilesOf(INITIAL_COMMIT.id, FIRST_PARENT_INDEX) shouldBe ChangedFilesUiState.Loading
    }

    test("기준 부모를 바꾸면 앞 기준의 변경 파일 목록이 새 기준에 남지 않는다") {
        val gateway = FakeDiffGateway(filesByParentIndex = mapOf(0 to listOf(fileChangeOf("first.kt"))))
        val state = CommitDetailState(useCaseOf(gateway))

        state.load(MERGE_COMMIT.id, 0)

        state.changedFilesOf(MERGE_COMMIT.id, 1) shouldBe ChangedFilesUiState.Loading
    }

    test("앞 조회가 실패해도 실패 안내가 다음 선택에 남지 않는다") {
        val state = CommitDetailState(
            useCaseOf(FakeDiffGateway(failure = UndineException.GitOperationFailed("changedFiles"))),
        )

        state.load(MERGE_COMMIT.id, FIRST_PARENT_INDEX)

        state.changedFilesOf(INITIAL_COMMIT.id, FIRST_PARENT_INDEX) shouldBe ChangedFilesUiState.Loading
        state.changedFilesOf(MERGE_COMMIT.id, 1) shouldBe ChangedFilesUiState.Loading
    }

    test("본문 접기는 커밋마다 따로 기억되고 다시 누르면 접힌다") {
        val state = CommitDetailState(useCaseOf(FakeDiffGateway()))

        state.isMessageExpanded(MERGE_COMMIT) shouldBe false

        state.toggleMessage(MERGE_COMMIT)
        state.isMessageExpanded(MERGE_COMMIT) shouldBe true
        state.isMessageExpanded(INITIAL_COMMIT) shouldBe false

        state.toggleMessage(MERGE_COMMIT)
        state.isMessageExpanded(MERGE_COMMIT) shouldBe false
    }
})
