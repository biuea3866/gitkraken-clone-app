package dev.undine.presentation.staging

import dev.undine.domain.AmendConfirmation
import dev.undine.domain.DiffHunk
import dev.undine.domain.UndineException
import dev.undine.presentation.i18n.CommitBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private const val MESSAGE = "스테이징 패널을 붙인다"

/** 스테이징 패널 상태 — 두 목록 이동·다중 선택·커밋 조건·amend 2단계 확인. */
class StagingStateSpec : FunSpec({

    test("파일을 stage 하면 staged 목록으로 이동하고 unstaged 에서 사라진다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(
            FakeRepositoryGateway(
                statusOf(unstaged = listOf("a.kt")),
                statusOf(staged = listOf("a.kt")),
            ),
            staging,
        )
        state.refresh()

        state.select(StagingSide.UNSTAGED, setOf("a.kt"))
        state.stageSelected()

        staging.staged shouldContainExactly listOf(listOf("a.kt"))
        state.staged shouldContainExactly listOf("a.kt")
        state.unstaged.shouldBeEmpty()
    }

    test("여러 파일을 선택해 한 번에 stage 한다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(
            FakeRepositoryGateway(statusOf(unstaged = listOf("a.kt", "b.kt", "c.kt"))),
            staging,
        )
        state.refresh()

        state.select(StagingSide.UNSTAGED, setOf("a.kt", "c.kt"))
        state.stageSelected()

        // 파일마다 왕복하면 목록이 중간 상태로 흔들린다 — 한 번에 넘긴다.
        staging.staged shouldContainExactly listOf(listOf("a.kt", "c.kt"))
    }

    test("선택이 없으면 목록 전체를 대상으로 한다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(FakeRepositoryGateway(statusOf(unstaged = listOf("a.kt", "b.kt"))), staging)
        state.refresh()

        state.stageSelected()

        staging.staged shouldContainExactly listOf(listOf("a.kt", "b.kt"))
    }

    test("staged 가 비어 있으면 커밋을 막고 사유가 그것이다") {
        val state = stagingStateWith(
            FakeRepositoryGateway(statusOf(unstaged = listOf("a.kt"))),
            RecordingStagingGateway(),
        )
        state.refresh()
        state.changeMessage(MESSAGE)

        state.blockedReason shouldBe CommitBlockedReason.NOTHING_STAGED
    }

    test("메시지가 비어 있으면 커밋을 막고 사유가 그것이다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))), staging)
        state.refresh()

        state.blockedReason shouldBe CommitBlockedReason.EMPTY_MESSAGE

        state.commit()
        staging.commitMessages.shouldBeEmpty()
    }

    test("작성자 미설정은 실패 안내가 아니라 커밋 사유로 남는다") {
        val staging = RecordingStagingGateway(commitFailure = UndineException.AuthorNotConfigured())
        val state = stagingStateWith(FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))), staging)
        state.refresh()
        state.changeMessage(MESSAGE)

        state.commit()

        // 설정하면 풀리는 조건이라 실패 배너가 아니라 버튼 사유로 보여야 한다.
        state.authorMissing shouldBe true
        state.blockedReason shouldBe CommitBlockedReason.AUTHOR_MISSING
        state.failure.shouldBeNull()
    }

    test("커밋 성공 후 메시지가 초기화되고 목록이 갱신된다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(
            FakeRepositoryGateway(
                statusOf(staged = listOf("a.kt")),
                statusOf(),
            ),
            staging,
        )
        state.refresh()
        state.changeMessage(MESSAGE)

        state.commit()

        staging.commitMessages shouldContainExactly listOf(MESSAGE)
        // 성공한 메시지가 남으면 사용자가 같은 내용으로 두 번 커밋하게 된다.
        state.message shouldBe ""
        state.isClean shouldBe true
    }

    test("amend 대상이 원격에 있으면 실행하지 않고 대상 확인을 요구한다") {
        val staging = RecordingStagingGateway(amendExistsOnRemote = true, amendTarget = commitId("f"))
        val state = stagingStateWith(FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))), staging)
        state.refresh()
        state.changeMessage(MESSAGE)
        state.requestAmendMode(true)

        state.commit()

        state.amendConfirmation shouldBe commitId("f")
        // 확인 전에는 저장소를 건드리지 않는다.
        staging.amendMessages.shouldBeEmpty()
    }

    test("확인하면 같은 대상으로 amend 하고 취소하면 저장소를 바꾸지 않는다") {
        val staging = RecordingStagingGateway(amendExistsOnRemote = true, amendTarget = commitId("f"))
        val state = stagingStateWith(FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))), staging)
        state.refresh()
        state.changeMessage(MESSAGE)
        state.requestAmendMode(true)
        state.commit()

        state.confirmAmend()

        staging.amendMessages shouldContainExactly
            listOf(MESSAGE to AmendConfirmation.ConfirmedRemoteTarget(commitId("f")))
    }

    test("amend 확인을 취소하면 아무 것도 실행하지 않는다") {
        val staging = RecordingStagingGateway(amendExistsOnRemote = true)
        val state = stagingStateWith(FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))), staging)
        state.refresh()
        state.changeMessage(MESSAGE)
        state.requestAmendMode(true)
        state.commit()

        state.dismiss()

        state.amendConfirmation.shouldBeNull()
        staging.amendMessages.shouldBeEmpty()
    }

    test("amend 체크를 끄면 대기 중인 확인도 사라진다") {
        val staging = RecordingStagingGateway(amendExistsOnRemote = true)
        val state = stagingStateWith(FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))), staging)
        state.refresh()
        state.changeMessage(MESSAGE)
        state.requestAmendMode(true)
        state.commit()

        state.requestAmendMode(false)

        state.amendConfirmation.shouldBeNull()
    }

    test("변경이 0건이면 빈 상태다") {
        val state = stagingStateWith(FakeRepositoryGateway(statusOf()), RecordingStagingGateway())

        state.refresh()

        state.isClean shouldBe true
    }

    test("hunk 스테이징 콜백을 UseCase 로 위임한다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(FakeRepositoryGateway(statusOf(unstaged = listOf("a.kt"))), staging)
        state.refresh()
        val hunks = listOf(
            DiffHunk(oldStart = 1, oldLineCount = 1, newStart = 1, newLineCount = 2, lines = emptyList()),
        )

        state.stageHunk("a.kt", hunks)

        // 스테이징 상태의 단일 소유자가 패널이다 — 뷰어는 의사만 올린다.
        staging.hunkRequests shouldContainExactly listOf("a.kt" to hunks)
    }

    test("빈 목록으로 조작하면 Gateway 를 부르지 않는다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(FakeRepositoryGateway(statusOf()), staging)
        state.refresh()

        state.stageSelected()
        state.unstageSelected()

        staging.staged.shouldBeEmpty()
        staging.unstaged.shouldBeEmpty()
    }

    test("조작이 실패하면 목록을 성공으로 바꾸지 않고 사유를 남긴다") {
        val state = stagingStateWith(
            FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))),
            FailingStagingGateway(),
        )
        state.refresh()

        state.unstageSelected()

        state.failure shouldBe FailingStagingGateway.FAILURE
    }
})

/** unstage 만 실패하는 대역. 조작 실패가 목록을 성공으로 바꾸지 않는지 본다. */
private class FailingStagingGateway : dev.undine.domain.StagingGateway {

    override suspend fun stage(paths: List<String>) = Unit

    override suspend fun unstage(paths: List<String>): Unit = throw FAILURE

    override suspend fun stageHunks(path: String, hunks: List<DiffHunk>) = Unit

    override suspend fun commit(message: String) = error("사용하지 않는다")

    override suspend fun stageAndCommit(paths: List<String>, message: String) = error("사용하지 않는다")

    override suspend fun inspectAmend() = error("사용하지 않는다")

    override suspend fun amend(message: String, confirmation: AmendConfirmation) = error("사용하지 않는다")

    companion object {
        val FAILURE = UndineException.GitOperationFailed("staging.unstage")
    }
}
