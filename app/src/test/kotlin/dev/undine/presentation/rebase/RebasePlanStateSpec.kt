package dev.undine.presentation.rebase

import dev.undine.domain.UndineException
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebaseAction
import dev.undine.domain.rebase.RebaseRunProgress
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** 계획 상태 홀더 — 편집은 저장소 무변경, 적용만 저장소를 바꾼다. */
class RebasePlanStateSpec : FunSpec({

    test("대상을 읽으면 전부 pick 인 계획이 열린다") {
        val state = rebaseStateWith(RecordingRebaseGateway(targetsOf("첫", "둘")))

        state.load()

        state.plan.shouldNotBeNull().steps.map { it.commit.message } shouldContainExactly listOf("첫", "둘")
        state.isEmpty shouldBe false
        state.canApply shouldBe true
    }

    test("대상이 없으면 빈 상태다") {
        val state = rebaseStateWith(RecordingRebaseGateway(targetsOf()))

        state.load()

        state.isEmpty shouldBe true
        state.canApply shouldBe false
    }

    test("계획을 편집하는 동안 저장소는 한 번도 바뀌지 않는다") {
        val gateway = RecordingRebaseGateway(targetsOf("첫", "둘", "셋"))
        val state = rebaseStateWith(gateway)
        state.load()

        state.move(from = 2, to = 0)
        state.setAction(1, RebaseAction.Squash)
        state.setAction(2, RebaseAction.Reword("고침"))
        state.reword(2, "다시 고침")

        // 편집은 불변 계획을 갈아끼우는 일이라 Gateway 를 부르지 않는다.
        gateway.appliedPlans.shouldBeEmpty()
        state.plan.shouldNotBeNull().steps.map { it.commit.message } shouldContainExactly
            listOf("셋", "첫", "둘")
    }

    test("첫 줄에 합치기를 지정하면 적용이 막히고 사유가 남는다") {
        val gateway = RecordingRebaseGateway(targetsOf("첫", "둘"))
        val state = rebaseStateWith(gateway)
        state.load()

        state.setAction(0, RebaseAction.Squash)

        state.canApply shouldBe false
        state.violations.size shouldBe 1
        state.apply()
        // 누를 수 없는 상태에서 불러도 저장소로 가지 않는다.
        gateway.appliedPlans.shouldBeEmpty()
    }

    test("전부 버리면 적용이 막힌다") {
        val state = rebaseStateWith(RecordingRebaseGateway(targetsOf("첫", "둘")))
        state.load()

        state.setAction(0, RebaseAction.Drop)
        state.setAction(1, RebaseAction.Drop)

        state.canApply shouldBe false
    }

    test("reword 가 아닌 줄의 메시지 편집은 무시한다") {
        val state = rebaseStateWith(RecordingRebaseGateway(targetsOf("첫")))
        state.load()

        state.reword(0, "몰래 바꾸기")

        state.plan.shouldNotBeNull().steps.first().action shouldBe RebaseAction.Pick
    }

    test("적용하면 계획이 저장소로 가고 결과가 남는다") {
        val gateway = RecordingRebaseGateway(
            targets = targetsOf("첫", "둘"),
            outcome = completedOutcome(),
            progress = null,
        )
        val state = rebaseStateWith(gateway)
        state.load()

        state.apply()

        gateway.appliedPlans.size shouldBe 1
        state.outcome shouldBe completedOutcome()
        state.applying shouldBe false
    }

    test("충돌은 실패가 아니라 결과로 남고 진행률을 읽어 온다") {
        val gateway = RecordingRebaseGateway(
            targets = targetsOf("첫", "둘"),
            outcome = InteractiveRebaseOutcome.Conflicted(listOf("shared.txt")),
            progress = RebaseRunProgress(applied = 1, total = 2),
        )
        val state = rebaseStateWith(gateway)
        state.load()

        state.apply()

        state.outcome shouldBe InteractiveRebaseOutcome.Conflicted(listOf("shared.txt"))
        state.failure.shouldBeNull()
        // 몇 번째 커밋을 적용 중인지 화면이 보여줄 재료다.
        state.progress shouldBe RebaseRunProgress(applied = 1, total = 2)
    }

    test("적용이 상태 위반으로 거부되면 실패로 남는다") {
        val gateway = RecordingRebaseGateway(
            targets = targetsOf("첫"),
            failOnApply = UndineException.StateViolation("이미 진행 중"),
        )
        val state = rebaseStateWith(gateway)
        state.load()

        state.apply()

        state.failure.shouldNotBeNull()
        state.applying shouldBe false
    }

    test("취소하면 계획이 폐기되고 저장소는 그대로다") {
        val gateway = RecordingRebaseGateway(targetsOf("첫", "둘"))
        val state = rebaseStateWith(gateway)
        state.load()
        state.setAction(1, RebaseAction.Drop)

        state.discard()

        state.plan.shouldBeNull()
        gateway.appliedPlans.shouldBeEmpty()
    }

    test("이미 원격에 올라간 커밋을 다시 쓰면 경고 대상이 된다") {
        val state = rebaseStateWith(
            RecordingRebaseGateway(targetsOf("원격", "로컬", pushed = setOf("원격"))),
        )
        state.load()

        state.rewritesPushedCommits shouldBe false
        state.setAction(0, RebaseAction.Reword("고침"))
        state.rewritesPushedCommits shouldBe true
    }

    test("'멈추고 편집' 을 지정하면 실행 중 멈춤을 미리 알린다") {
        val state = rebaseStateWith(RecordingRebaseGateway(targetsOf("첫")))
        state.load()

        state.stopsDuringRun shouldBe false
        state.setAction(0, RebaseAction.Edit)
        state.stopsDuringRun shouldBe true
    }

    test("대상 읽기가 실패하면 빈 계획이 아니라 실패로 남는다") {
        val gateway = FailingListGateway()
        val state = RebasePlanState(
            actions = rebaseActionsOf(gateway),
            upstream = { UPSTREAM },
            scope = unconfinedScope(),
        )

        state.load()

        state.failure.shouldNotBeNull()
        state.plan.shouldBeNull()
    }
})
