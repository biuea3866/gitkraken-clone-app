package dev.undine.domain.rebase

import dev.undine.testsupport.commit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** 리베이스 계획 — 재정렬·동작 지정·검증·미리보기. 저장소를 알지 못하는 순수 값이다. */
class RebasePlanSpec : FunSpec({

    test("대상 전부를 pick 으로 여는 계획을 만든다") {
        val plan = planOf("첫", "둘", "셋")

        plan.steps.map { it.action } shouldContainExactly
            listOf(RebaseAction.Pick, RebaseAction.Pick, RebaseAction.Pick)
        plan.isApplicable shouldBe true
        plan.violations().shouldBeEmpty()
    }

    test("순서를 바꾸면 계획에 반영되고 원본은 그대로다") {
        val plan = planOf("첫", "둘", "셋")

        val moved = plan.move(from = 2, to = 0)

        moved.messages() shouldContainExactly listOf("셋", "첫", "둘")
        // 불변 값이라 원본은 흔들리지 않는다 — 취소하면 편집 전으로 돌아갈 수 있다.
        plan.messages() shouldContainExactly listOf("첫", "둘", "셋")
    }

    test("범위를 벗어난 재정렬·동작 지정은 무시한다") {
        val plan = planOf("첫", "둘")

        plan.move(from = 0, to = 5) shouldBe plan
        plan.move(from = -1, to = 0) shouldBe plan
        plan.move(from = 1, to = 1) shouldBe plan
        plan.withAction(9, RebaseAction.Drop) shouldBe plan
    }

    test("첫 줄에 squash 나 fixup 을 지정하면 합칠 앞 커밋이 없어 실행이 막힌다") {
        val plan = planOf("첫", "둘")

        val squashed = plan.withAction(0, RebaseAction.Squash)

        squashed.isApplicable shouldBe false
        squashed.violations() shouldContainExactly listOf(RebasePlanViolation.FirstStepCannotAbsorb)
        plan.withAction(0, RebaseAction.Fixup).violations() shouldContainExactly
            listOf(RebasePlanViolation.FirstStepCannotAbsorb)
    }

    test("첫 줄을 다른 커밋으로 옮기면 같은 squash 가 실행 가능해진다") {
        val plan = planOf("첫", "둘").withAction(0, RebaseAction.Squash)

        plan.move(from = 0, to = 1).isApplicable shouldBe true
    }

    test("전부 drop 하면 결과가 빈 리베이스라 실행이 막힌다") {
        val plan = planOf("첫", "둘")
            .withAction(0, RebaseAction.Drop)
            .withAction(1, RebaseAction.Drop)

        plan.violations() shouldContainExactly listOf(RebasePlanViolation.EverythingDropped)
    }

    test("대상이 없는 계획은 위반도 없다") {
        // "전부 drop" 은 drop 이 하나라도 있을 때의 이야기다 — 빈 계획을 같은 이유로 막지 않는다.
        RebasePlan.of(emptyList()).violations().shouldBeEmpty()
    }

    test("squash 로 묶인 커밋들이 미리보기에서 앞 커밋 하나로 모인다") {
        val plan = planOf("기반", "고침1", "고침2", "다음")
            .withAction(1, RebaseAction.Squash)
            .withAction(2, RebaseAction.Fixup)

        val preview = plan.preview()

        preview.size shouldBe 2
        val first = preview.first().shouldBeInstanceOf<RebasePreviewEntry.Kept>()
        first.step.commit.message shouldBe "기반"
        first.absorbed.map { it.commit.message } shouldContainExactly listOf("고침1", "고침2")
        preview.last().shouldBeInstanceOf<RebasePreviewEntry.Kept>().step.commit.message shouldBe "다음"
    }

    test("drop 된 커밋은 미리보기에서 따로 표시되고 흡수 대상이 되지 않는다") {
        val plan = planOf("기반", "버릴것", "고침")
            .withAction(1, RebaseAction.Drop)
            .withAction(2, RebaseAction.Squash)

        val preview = plan.preview()

        preview[1].shouldBeInstanceOf<RebasePreviewEntry.Dropped>().step.commit.message shouldBe "버릴것"
        // 흡수는 drop 을 건너뛰고 살아남는 앞 커밋으로 간다 — 결과에 없는 커밋에 합칠 수는 없다.
        val kept = preview[0].shouldBeInstanceOf<RebasePreviewEntry.Kept>()
        kept.absorbed.map { it.commit.message } shouldContainExactly listOf("고침")
    }

    test("실행 중 멈추는 줄이 있으면 표시할 수 있다") {
        val plan = planOf("첫", "둘")

        plan.stopsDuringRun shouldBe false
        // reword 는 메시지를 계획에 이미 담아 멈추지 않는다.
        plan.withAction(1, RebaseAction.Reword("새 메시지")).stopsDuringRun shouldBe false
        plan.withAction(1, RebaseAction.Edit).stopsDuringRun shouldBe true
    }

    test("이미 push 된 커밋을 다시 쓰면 이력 분기 경고 대상이다") {
        val plan = RebasePlan.of(
            listOf(
                RebaseTarget(commit(1, message = "원격에 있음"), isPushed = true),
                RebaseTarget(commit(2, message = "로컬만"), isPushed = false),
            ),
        )

        // pick 만이면 이력이 그대로라 경고하지 않는다.
        plan.rewritesPushedCommits shouldBe false
        plan.withAction(0, RebaseAction.Drop).rewritesPushedCommits shouldBe true
        plan.withAction(1, RebaseAction.Drop).rewritesPushedCommits shouldBe false
    }
})

private fun planOf(vararg messages: String): RebasePlan = RebasePlan.of(
    messages.mapIndexed { index, message ->
        RebaseTarget(commit(index + 1, message = message), isPushed = false)
    },
)

private fun RebasePlan.messages(): List<String> = steps.map { it.commit.message }
