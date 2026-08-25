package dev.undine.domain.externaltool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private const val FAILED_EXIT_CODE = 3

/**
 * 외부 도구 결과가 **닫힌 집합**임을 검증한다.
 *
 * 아래 두 `when` 에는 `else` 가 없다 — 변이를 하나 늘리면 컴파일이 깨지고, 화면이 새 사유를
 * 조용히 뭉개는 일이 생기지 않는다. 사유를 구분하는 것이 이 타입의 존재 이유라 라벨이 겹치는지도 본다.
 */
class ExternalToolResultSpec : FunSpec({

    test("diff 결과는 성공·실패·미설정·미설치·설정 오류를 서로 다른 분기로 구분한다") {
        val results = listOf(
            DiffToolResult.Completed,
            DiffToolResult.ToolFailed(FAILED_EXIT_CODE),
            ExternalToolUnavailable.NoToolConfigured,
            ExternalToolUnavailable.ToolNotFound("meld"),
            ExternalToolUnavailable.MisconfiguredTool("meld", "cmd 없음"),
        )

        val labels = results.map { result ->
            when (result) {
                is DiffToolResult.Completed -> "완료"
                is DiffToolResult.ToolFailed -> "실패:${result.exitCode}"
                is ExternalToolUnavailable.NoToolConfigured -> "미설정"
                is ExternalToolUnavailable.ToolNotFound -> "미설치:${result.executable}"
                is ExternalToolUnavailable.MisconfiguredTool -> "설정오류:${result.toolName}"
            }
        }

        labels.toSet() shouldHaveSize results.size
    }

    test("merge 결과는 변경됨·변경 없음·병합 실패·미설정·미설치·설정 오류를 서로 다른 분기로 구분한다") {
        val results = listOf(
            MergeToolResult.Resolved("합쳐진 내용"),
            MergeToolResult.Unchanged,
            MergeToolResult.MergeFailed(FAILED_EXIT_CODE),
            ExternalToolUnavailable.NoToolConfigured,
            ExternalToolUnavailable.ToolNotFound("kdiff3"),
            ExternalToolUnavailable.MisconfiguredTool("kdiff3", "cmd 없음"),
        )

        val labels = results.map { result ->
            when (result) {
                is MergeToolResult.Resolved -> "해결됨:${result.content}"
                is MergeToolResult.Unchanged -> "변경없음"
                is MergeToolResult.MergeFailed -> "병합실패:${result.exitCode}"
                is ExternalToolUnavailable.NoToolConfigured -> "미설정"
                is ExternalToolUnavailable.ToolNotFound -> "미설치:${result.executable}"
                is ExternalToolUnavailable.MisconfiguredTool -> "설정오류:${result.toolName}"
            }
        }

        labels.toSet() shouldHaveSize results.size
    }

    test("띄우지 못한 사유는 diff 와 merge 양쪽 결과로 그대로 쓰인다") {
        // 같은 사유를 두 번 정의하면 한쪽만 늘어난다 — 공유 타입임을 계약으로 고정한다.
        val unavailable: ExternalToolUnavailable = ExternalToolUnavailable.NoToolConfigured

        (unavailable as DiffToolResult) shouldBe unavailable
        (unavailable as MergeToolResult) shouldBe unavailable
    }
})
