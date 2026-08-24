package dev.undine.domain.conflict

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** git 이 실제로 쓰는 표식 모양. 라벨(브랜치명)이 붙는 것까지 포함한다. */
private val CONFLICTED = """
    앞
    <<<<<<< HEAD
    우리 줄
    =======
    저쪽 줄
    >>>>>>> feature
    뒤
""".trimIndent()

private val DIFF3 = """
    <<<<<<< HEAD
    우리 줄
    ||||||| base
    원래 줄
    =======
    저쪽 줄
    >>>>>>> feature
""".trimIndent()

/** 충돌 문서 파싱·선택·렌더 — 표식 잔존 검사가 저장을 막는 근거다. */
class ConflictDocumentSpec : FunSpec({

    test("표식을 기준으로 안정 구간과 충돌 구간으로 나눈다") {
        val document = ConflictDocument.parse(CONFLICTED)

        document.conflictCount shouldBe 1
        document.segments.size shouldBe 3
        document.segments[0].shouldBeInstanceOf<ConflictSegment.Stable>().lines shouldContainExactly listOf("앞")
        val conflict = document.segments[1].shouldBeInstanceOf<ConflictSegment.Conflict>()
        conflict.ours shouldContainExactly listOf("우리 줄")
        conflict.theirs shouldContainExactly listOf("저쪽 줄")
        conflict.base.shouldBeEmpty()
        document.segments[2].shouldBeInstanceOf<ConflictSegment.Stable>().lines shouldContainExactly listOf("뒤")
    }

    test("diff3 표식이면 base 도 함께 읽는다") {
        val conflict = ConflictDocument.parse(DIFF3)
            .segments
            .first()
            .shouldBeInstanceOf<ConflictSegment.Conflict>()

        conflict.base shouldContainExactly listOf("원래 줄")
    }

    test("ours 를 채택하면 결과에 그 내용만 남는다") {
        val document = ConflictDocument.parse(CONFLICTED)
            .choose(0, ConflictChoice.Take(ConflictSide.OURS))

        document.render() shouldBe "앞\n우리 줄\n뒤"
        document.isResolved shouldBe true
    }

    test("theirs 를 채택하면 저쪽 내용만 남는다") {
        ConflictDocument.parse(CONFLICTED)
            .choose(0, ConflictChoice.Take(ConflictSide.THEIRS))
            .render() shouldBe "앞\n저쪽 줄\n뒤"
    }

    test("둘 다 를 고르면 ours 다음 theirs 순서로 들어간다") {
        ConflictDocument.parse(CONFLICTED)
            .choose(0, ConflictChoice.TakeBoth)
            .render() shouldBe "앞\n우리 줄\n저쪽 줄\n뒤"
    }

    test("직접 편집한 내용으로 대체할 수 있다") {
        ConflictDocument.parse(CONFLICTED)
            .choose(0, ConflictChoice.Edited(listOf("합친 줄")))
            .render() shouldBe "앞\n합친 줄\n뒤"
    }

    test("고르지 않은 구간은 표식을 그대로 남기고 잔존 위치를 알린다") {
        val document = ConflictDocument.parse(CONFLICTED)

        // 임의로 한쪽을 고르면 사용자가 보지 않은 내용이 저장된다 — 표식을 남겨 저장을 막는다.
        document.isResolved shouldBe false
        document.unresolvedLineNumbers() shouldContainExactly listOf(2, 4, 6)
    }

    test("해결하면 잔존 표식이 없다") {
        ConflictDocument.parse(CONFLICTED)
            .choose(0, ConflictChoice.Take(ConflictSide.OURS))
            .unresolvedLineNumbers()
            .shouldBeEmpty()
    }

    test("사용자가 편집으로 표식을 다시 써 넣으면 잔존으로 잡는다") {
        val document = ConflictDocument.parse(CONFLICTED)
            .choose(0, ConflictChoice.Edited(listOf("<<<<<<< 실수로 남긴 표식", "내용")))

        // 표식이 남은 채 스테이징되면 그대로 커밋되어 소스에 박힌다.
        document.isResolved shouldBe true
        document.unresolvedLineNumbers() shouldContainExactly listOf(2)
    }

    test("충돌이 여러 개면 각각 따로 고른다") {
        val twoConflicts = """
            <<<<<<< HEAD
            a1
            =======
            b1
            >>>>>>> f
            중간
            <<<<<<< HEAD
            a2
            =======
            b2
            >>>>>>> f
        """.trimIndent()

        val document = ConflictDocument.parse(twoConflicts)
        document.conflictCount shouldBe 2

        val partial = document.choose(0, ConflictChoice.Take(ConflictSide.OURS))
        partial.unresolvedCount shouldBe 1

        partial.choose(1, ConflictChoice.Take(ConflictSide.THEIRS))
            .render() shouldBe "a1\n중간\nb2"
    }

    test("충돌이 없는 파일은 통째로 안정 구간이고 그대로 렌더된다") {
        val plain = "한 줄\n두 줄"
        val document = ConflictDocument.parse(plain)

        document.conflictCount shouldBe 0
        document.isResolved shouldBe true
        document.render() shouldBe plain
    }

    test("닫는 표식 없이 끝난 충돌도 구간으로 확정해 내용을 버리지 않는다") {
        val truncated = "<<<<<<< HEAD\n우리 줄\n=======\n저쪽 줄"

        val conflict = ConflictDocument.parse(truncated)
            .segments
            .single()
            .shouldBeInstanceOf<ConflictSegment.Conflict>()

        conflict.ours shouldContainExactly listOf("우리 줄")
        conflict.theirs shouldContainExactly listOf("저쪽 줄")
    }

    test("충돌 밖에서 만난 표식 모양 줄은 본문으로 둔다") {
        // 문서·테스트가 표식을 예시로 적을 수 있다 — 여는 표식 없이 나온 구분선은 충돌이 아니다.
        val document = ConflictDocument.parse("설명\n=======\n밑줄")

        document.conflictCount shouldBe 0
        document.render() shouldBe "설명\n=======\n밑줄"
    }

    test("선택은 원본을 바꾸지 않는다") {
        val original = ConflictDocument.parse(CONFLICTED)

        original.choose(0, ConflictChoice.TakeBoth)

        original.unresolvedCount shouldBe 1
    }
})
