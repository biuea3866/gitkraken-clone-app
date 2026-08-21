package dev.undine.infrastructure.git.diff

import dev.undine.domain.DiffLineType
import dev.undine.domain.DiffResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.eclipse.jgit.api.Git
import java.io.File

private val BINARY_BEFORE = byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x03)
private val BINARY_AFTER = byteArrayOf(0x00, 0x04, 0x05, 0x00, 0x06)

/** 정확히 1 MiB 가 되는 한 줄(내용 1048575 바이트 + 개행). */
private fun lineOfSize(filler: Char, totalBytes: Int): String = "${filler.toString().repeat(totalBytes - 1)}\n"

/** hunk 지연 계산 · 이진 · 대용량 · rename · word-level 강조. */
class DiffHunkComputationSpec : FunSpec({

    val root = tempdir()
    val opened = mutableListOf<Git>()

    afterSpec { opened.forEach { git -> git.close() } }

    fun newRepository(): Git = initRepository(File(root, "hunk-${opened.size}")).also { opened += it }

    /**
     * 지연 계산을 **관측해서** 확인한다. 목록 조회도 증감 줄 수를 위해 blob 은 읽지만
     * (`FileChange.addedLines` 계약), hunk 본문은 한 줄도 만들지 않아야 한다 —
     * 그것이 대형 저장소에서 목록 렌더링을 멈추게 하는 비용이다.
     */
    test("파일 목록 조회는 hunk 본문을 만들지 않고 hunksOf 만 선택 파일에 한 번 만든다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\ntwo\nthree\n")
        git.writeFile("b.txt", "four\nfive\nsix\n")
        git.commitAll("first")
        git.writeFile("a.txt", "one\nTWO\nthree\n")
        git.writeFile("b.txt", "four\nfive\nSIX\n")
        val second = git.commitAll("second")
        git.writeFile("c.txt", "seven\n")
        git.stageAll()
        git.writeFile("c.txt", "eight\n")
        val gateway = git.diffGateway()

        mockkObject(HunkBuilder)
        try {
            gateway.changedFiles(second.id(), 0) shouldHaveSize 2
            gateway.changedFilesStaged() shouldHaveSize 1
            gateway.changedFilesUnstaged() shouldHaveSize 1
            verify(exactly = 0) { HunkBuilder.build(any(), any(), any()) }

            gateway.hunksOf(second.id(), "a.txt", 0)
                .shouldBeInstanceOf<DiffResult.Computed>().hunks shouldHaveSize 1
            verify(exactly = 1) { HunkBuilder.build(any(), any(), any()) }
        } finally {
            unmockkObject(HunkBuilder)
        }
    }

    test("hunk 는 요청한 파일에만 계산되고 다른 변경 파일 내용이 섞이지 않는다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\ntwo\nthree\n")
        git.writeFile("b.txt", "four\nfive\nsix\n")
        git.commitAll("first")
        git.writeFile("a.txt", "one\nTWO\nthree\n")
        git.writeFile("b.txt", "four\nfive\nSIX\n")
        val second = git.commitAll("second")
        val gateway = git.diffGateway()

        gateway.changedFiles(second.id(), 0) shouldHaveSize 2
        val computed = gateway.hunksOf(second.id(), "a.txt", 0).shouldBeInstanceOf<DiffResult.Computed>()

        computed.hunks shouldHaveSize 1
        computed.hunks.single().lines.forAll { line -> line.content shouldNotContain "SIX" }
        computed.hunks.single().lines.map { line -> line.type } shouldContainExactly listOf(
            DiffLineType.CONTEXT,
            DiffLineType.DELETED,
            DiffLineType.ADDED,
            DiffLineType.CONTEXT,
        )
    }

    test("이 커밋에서 변경되지 않은 경로는 hunk 가 없다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\n")
        git.writeFile("b.txt", "two\n")
        git.commitAll("first")
        git.writeFile("a.txt", "ONE\n")
        val second = git.commitAll("second")

        val computed = git.diffGateway().hunksOf(second.id(), "b.txt", 0)

        computed.shouldBeInstanceOf<DiffResult.Computed>().hunks.shouldBeEmpty()
    }

    test("rename 된 파일의 hunk 는 새 경로로 조회된다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\ntwo\nthree\nfour\nfive\n")
        git.commitAll("first")
        File(git.repository.workTree, "a.txt").delete()
        git.writeFile("b.txt", "one\nTWO\nthree\nfour\nfive\n")
        val renamed = git.commitAll("rename")
        val gateway = git.diffGateway()
        val change = gateway.changedFiles(renamed.id(), 0).single()

        val computed = gateway.hunksOf(renamed.id(), change.path, 0).shouldBeInstanceOf<DiffResult.Computed>()

        change.path shouldBe "b.txt"
        change.previousPath shouldBe "a.txt"
        computed.hunks.single().lines.first { line -> line.type == DiffLineType.ADDED }.content shouldBe "TWO"
    }

    test("이진 파일의 hunk 요청은 NotComputed(BINARY) 다") {
        val git = newRepository()
        git.writeBytes("blob.bin", BINARY_BEFORE)
        git.commitAll("first")
        git.writeBytes("blob.bin", BINARY_AFTER)
        val second = git.commitAll("second")
        val gateway = git.diffGateway()

        gateway.changedFiles(second.id(), 0).single().isBinary shouldBe true
        gateway.hunksOf(second.id(), "blob.bin", 0) shouldBe
            DiffResult.NotComputed(DiffResult.Reason.BINARY)
    }

    test("정확히 1 MiB 인 텍스트 파일은 hunk 를 계산한다") {
        val git = newRepository()
        val threshold = MAX_DIFF_BLOB_BYTES.toInt()
        git.writeFile("big.txt", lineOfSize('a', threshold))
        git.commitAll("first")
        git.writeFile("big.txt", lineOfSize('b', threshold))
        val second = git.commitAll("second")

        val result = git.diffGateway().hunksOf(second.id(), "big.txt", 0)

        result.shouldBeInstanceOf<DiffResult.Computed>().hunks shouldHaveSize 1
    }

    test("1 MiB 를 넘는 텍스트 파일은 NotComputed(TOO_LARGE) 로 사유를 밝힌다") {
        val git = newRepository()
        val threshold = MAX_DIFF_BLOB_BYTES.toInt()
        git.writeFile("big.txt", lineOfSize('a', threshold))
        git.commitAll("first")
        git.writeFile("big.txt", lineOfSize('b', threshold + 1))
        val second = git.commitAll("second")
        val gateway = git.diffGateway()

        gateway.hunksOf(second.id(), "big.txt", 0) shouldBe
            DiffResult.NotComputed(DiffResult.Reason.TOO_LARGE)
        gateway.changedFiles(second.id(), 0).map { change -> change.path } shouldContainExactly listOf("big.txt")
    }

    test("word-level 강조는 변경된 줄 쌍에만 생기고 context 줄에는 없다") {
        val git = newRepository()
        git.writeFile("a.txt", "line one\nval a = 1\nline three\n")
        git.commitAll("first")
        git.writeFile("a.txt", "line one\nval a = 2\nline three\n")
        val second = git.commitAll("second")

        val computed = git.diffGateway()
            .hunksOf(second.id(), "a.txt", 0)
            .shouldBeInstanceOf<DiffResult.Computed>()
        val hunk = computed.hunks.single()

        hunk.oldStart shouldBe 1
        hunk.oldLineCount shouldBe 3
        hunk.newStart shouldBe 1
        hunk.newLineCount shouldBe 3
        hunk.lines.first { line -> line.type == DiffLineType.DELETED }.changedRanges shouldContainExactly
            listOf(8..8)
        hunk.lines.first { line -> line.type == DiffLineType.ADDED }.changedRanges shouldContainExactly
            listOf(8..8)
        hunk.lines.filter { line -> line.type == DiffLineType.CONTEXT }
            .forAll { line -> line.changedRanges.shouldBeEmpty() }
    }
})
