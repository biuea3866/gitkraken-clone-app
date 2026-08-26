package dev.undine.presentation.undo

import dev.undine.application.undo.UndoExecution
import dev.undine.application.undo.UndoTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.RepositoryBaseline
import dev.undine.domain.undo.UndoOutcome
import dev.undine.domain.undo.UndoStrategy
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.undoTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant

private val MAIN = RefName("main")
private val HEAD = CommitId.of("a".repeat(40))
private val PARENT = CommitId.of("b".repeat(40))
private val RECORDED_AT = Instant.parse("2026-08-25T01:02:03Z")

private fun entry(
    operation: GitOperationKind = GitOperationKind.COMMIT,
    strategy: UndoStrategy = UndoStrategy.SoftResetTo(PARENT),
    targetLabel: String = "로그인 수정",
    recordedAt: Instant = RECORDED_AT,
) = OperationEntry(
    operation = operation,
    strategy = strategy,
    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
    targetLabel = targetLabel,
    recordedAt = recordedAt,
)

private fun undoStrings() = StringCatalog(undoTranslations, DEFAULT_LOCALE)
    .stringsFor(DEFAULT_LOCALE, devBuild = true)

/**
 * Undo 화면의 표시 모델 — Compose 런타임 없이 문구·이력 순서·불가 사유를 검증한다.
 * 화면 렌더링은 이 모델만 소비하므로 상태 홀더에서 UI 테스트 없이도 계약을 고정할 수 있다.
 */
class UndoPresentationSpec : FunSpec({

    test("되돌릴 수 있는 최상단은 동작명을 포함한 버튼 레이블과 대상 툴팁을 준다") {
        val presentation = undoButtonPresentation(
            target = UndoTarget.Undoable(entry()),
            isUndoing = false,
            strings = undoStrings(),
        )

        presentation.label shouldBe "커밋 취소"
        presentation.tooltip shouldBe "커밋 취소 — 대상: 로그인 수정"
        presentation.disabledReason shouldBe null
        presentation.enabled shouldBe true
    }

    test("빈 스택은 지정 문구와 함께 Undo 버튼을 비활성화한다") {
        val presentation = undoButtonPresentation(UndoTarget.None, isUndoing = false, strings = undoStrings())

        presentation.enabled shouldBe false
        presentation.disabledReason shouldBe "되돌릴 작업이 없습니다"
    }

    test("복구 불가 push 는 domain 사유를 노출하지 않고 번역된 불가 문구를 보인다") {
        val presentation = undoButtonPresentation(
            target = UndoTarget.Blocked(
                entry(operation = GitOperationKind.PUSH, strategy = UndoStrategy.Irreversible("내부 진단 문구")),
                UndoOutcome.Irreversible(GitOperationKind.PUSH, "내부 진단 문구"),
            ),
            isUndoing = false,
            strings = undoStrings(),
        )

        presentation.enabled shouldBe false
        presentation.disabledReason shouldBe "push 는 되돌릴 수 없습니다"
    }

    test("외부 변경은 지정 문구와 함께 실행 전에 막는다") {
        val recorded = RepositoryBaseline(branch = MAIN, head = HEAD)
        val presentation = undoButtonPresentation(
            target = UndoTarget.Blocked(
                entry(),
                UndoOutcome.ExternalChange(recorded, recorded.copy(head = PARENT)),
            ),
            isUndoing = false,
            strings = undoStrings(),
        )

        presentation.enabled shouldBe false
        presentation.disabledReason shouldBe "저장소가 외부에서 변경되어 되돌릴 수 없습니다"
    }

    test("실행 뒤 거부된 외부 변경도 성공 문구가 아닌 지정 문구로 나타낸다") {
        val recorded = RepositoryBaseline(branch = MAIN, head = HEAD)

        undoExecutionMessage(
            UndoExecution.Completed(UndoOutcome.ExternalChange(recorded, recorded.copy(head = PARENT))),
            undoStrings(),
        ) shouldBe "저장소가 외부에서 변경되어 되돌릴 수 없습니다"
    }

    test("대상이 어긋나 아무것도 하지 않은 결말은 성공도 실패도 아닌 다시 확인 안내다") {
        undoExecutionMessage(UndoExecution.TargetChanged, undoStrings()) shouldBe
            "되돌릴 대상이 바뀌어 아무것도 실행하지 않았습니다. 다시 확인하세요."
    }

    test("실행 실패는 무엇이 왜 실패했는지 함께 말한다") {
        undoExecutionMessage(
            UndoExecution.Failed(entry(), UndineException.StateViolation("인덱스가 잠겨 있습니다")),
            undoStrings(),
        ) shouldBe "커밋 을(를) 되돌리지 못했습니다: 현재 저장소 상태에서 수행할 수 없습니다: 인덱스가 잠겨 있습니다"
    }

    test("기록을 지운 결말은 되돌렸다는 문구와 섞이지 않는다") {
        val discarded = UndoExecution.Discarded(
            entry(operation = GitOperationKind.PUSH, strategy = UndoStrategy.Irreversible("원격 반영")),
            UndoOutcome.Irreversible(GitOperationKind.PUSH, "원격 반영"),
        )

        undoExecutionMessage(discarded, undoStrings()) shouldBe
            "되돌릴 수 없는 push 기록을 이력에서 지웠습니다."
    }

    test("실행 이력은 받은 최신 우선 순서를 바꾸지 않고 시각·대상·가능 여부를 보존한다") {
        val newest = entry(targetLabel = "둘째 커밋", recordedAt = RECORDED_AT.plusSeconds(1))
        val older = entry(
            operation = GitOperationKind.PUSH,
            strategy = UndoStrategy.Irreversible("원격 반영"),
            targetLabel = "origin/main",
        )

        undoHistoryPresentation(listOf(newest, older), undoStrings()) shouldContainExactly listOf(
            UndoHistoryRow(
                entry = newest,
                availability = "되돌릴 수 있음",
                irreversibleReason = null,
            ),
            UndoHistoryRow(
                entry = older,
                availability = "되돌릴 수 없음",
                irreversibleReason = "원격 반영",
            ),
        )
    }
})
