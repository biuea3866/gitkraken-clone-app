package dev.undine.scenario2

import dev.undine.domain.blame.BlameResult
import dev.undine.domain.blame.LineRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

private const val CODE = "code.txt"
private const val RENAMED = "renamed.txt"
private const val HISTORY_LIMIT = 10

/**
 * 2차 시나리오 4 — 수정 이력을 쌓고 blame 을 본 뒤, rename 을 거쳐도 이력이 이어지는지 확인한다.
 *
 * rename 지점에서 이력이 끊기면 파일의 진짜 시작점을 볼 수 없다. 그래서 rename 전 커밋까지 이어지는지,
 * 그리고 그 지점의 이전 경로가 함께 오는지를 함께 본다.
 */
class BlameRenameScenario2Spec : FunSpec({

    test("수정 이력이 쌓인 파일의 blame 이 줄마다 마지막으로 고친 커밋을 지목한다") {
        openedApp { app ->
            app.writeFile(CODE, "한 줄\n")
            app.stageAndCommit("처음 만든다", CODE)
            app.writeFile(CODE, "한 줄\n두 줄\n")
            app.stageAndCommit("두 번째 줄을 넣는다", CODE)

            val blame = app.loadBlame.execute(CODE, LineRange.whole())

            val lines = blame.shouldBeInstanceOf<BlameResult.Lines>().lines
            lines.map { it.line } shouldContainExactly listOf(1, 2)
            lines.map { it.content } shouldContainExactly listOf("한 줄", "두 줄")
            lines.map { it.commit.message.trim() } shouldContainExactly
                listOf("처음 만든다", "두 번째 줄을 넣는다")
        }
    }

    test("rename 을 거친 뒤에도 blame 과 파일 이력이 rename 이전까지 이어진다") {
        openedApp { app ->
            app.writeFile(CODE, "한 줄\n")
            app.stageAndCommit("처음 만든다", CODE)
            app.writeFile(CODE, "한 줄\n두 줄\n")
            app.stageAndCommit("두 번째 줄을 넣는다", CODE)

            // 이름을 바꾼다 — 내용은 그대로라 rename 으로 탐지돼야 한다. 앱의 stage 경로가 삭제·추가를 함께 기록한다.
            File(app.work, CODE).renameTo(File(app.work, RENAMED))
            app.stageAndCommit("이름을 바꾼다", CODE, RENAMED)

            app.writeFile(RENAMED, "한 줄\n두 줄\n세 줄\n")
            app.stageAndCommit("이름 바꾼 뒤 고친다", RENAMED)

            val history = app.loadFileHistory.execute(RENAMED, limit = HISTORY_LIMIT)

            // rename 지점을 넘어 처음 만든 커밋까지 이어진다 (최신 우선).
            history.map { it.commit.message.trim() } shouldContainExactly listOf(
                "이름 바꾼 뒤 고친다",
                "이름을 바꾼다",
                "두 번째 줄을 넣는다",
                "처음 만든다",
            )
            history.map { it.path } shouldContainExactly listOf(RENAMED, RENAMED, CODE, CODE)
            // 이름이 바뀐 지점은 이전 경로를 함께 알려 준다 — 화면이 그 지점을 표시할 근거다.
            history[1].isRename shouldBe true
            history[1].previousPath shouldBe CODE
            history.filterIndexed { index, _ -> index != 1 }.map { it.isRename } shouldContainExactly
                listOf(false, false, false)

            // rename 뒤의 blame 도 rename 이전 커밋을 그대로 지목한다.
            val lines = app.loadBlame.execute(RENAMED, LineRange.whole())
                .shouldBeInstanceOf<BlameResult.Lines>().lines
            lines.map { it.commit.message.trim() } shouldContainExactly listOf(
                "처음 만든다",
                "두 번째 줄을 넣는다",
                "이름 바꾼 뒤 고친다",
            )
        }
    }
})
