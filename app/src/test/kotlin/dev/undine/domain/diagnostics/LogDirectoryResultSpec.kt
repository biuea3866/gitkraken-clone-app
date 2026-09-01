package dev.undine.domain.diagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Path

private const val OPEN_FAILURE_REASON = "파일 관리자를 띄우지 못했습니다"

/**
 * 로그 디렉터리 결과가 **닫힌 집합**임을 검증한다.
 *
 * 아래 `when` 에는 `else` 가 없다 — 변이를 하나 늘리면 컴파일이 깨지고, 화면이 '아직 없음' 을
 * 실패로 뭉개거나 열기 실패를 조용히 성공으로 접는 일이 생기지 않는다.
 */
class LogDirectoryResultSpec : FunSpec({

    test("조회 결과는 '있음' 과 '아직 없음' 을 서로 다른 분기로 구분한다") {
        val results = listOf(
            LogDirectoryLocation.Found(Path.of("/home/user/.undine")),
            LogDirectoryMissing,
        )

        val labels = results.map { result ->
            when (result) {
                is LogDirectoryLocation.Found -> "있음:${result.path}"
                is LogDirectoryMissing -> "아직없음"
            }
        }

        labels.toSet() shouldHaveSize results.size
    }

    test("열기 결과는 열림·아직 없음·실패를 서로 다른 분기로 구분하고 실패는 사유를 보존한다") {
        val results = listOf(
            OpenLogDirectoryResult.Opened,
            LogDirectoryMissing,
            OpenLogDirectoryResult.OpenFailed(OPEN_FAILURE_REASON),
        )

        val labels = results.map { result ->
            when (result) {
                is OpenLogDirectoryResult.Opened -> "열림"
                is LogDirectoryMissing -> "아직없음"
                is OpenLogDirectoryResult.OpenFailed -> "실패:${result.reason}"
            }
        }

        labels.toSet() shouldHaveSize results.size
        labels.last() shouldBe "실패:$OPEN_FAILURE_REASON"
    }

    test("'아직 없음' 은 조회와 열기 양쪽 결과로 그대로 쓰인다") {
        // 같은 사실을 두 번 정의하면 한쪽만 늘어난다 — 공유 타입임을 계약으로 고정한다.
        val missing = LogDirectoryMissing

        (missing as LogDirectoryLocation) shouldBe missing
        (missing as OpenLogDirectoryResult) shouldBe missing
    }
})
