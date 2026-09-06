package dev.undine.application.patch

import dev.undine.domain.DiffLineType
import dev.undine.domain.DiffResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 패치 바이트 → 도메인 hunk 변환 계약.
 *
 * 미리볼 수 없는 패치(binary·과대)를 **빈 hunk 목록으로 접지 않는다** — 그러면 화면이 "변경 없음"
 * 으로 그려져, 사용자는 실제로는 파일을 통째로 바꾸는 패치를 아무것도 안 하는 것으로 읽는다.
 */
class PatchPreviewSpec : FunSpec({

    test("일반 텍스트 패치는 hunk 헤더와 줄 종류·번호를 그대로 옮긴다") {
        val patch = """
            diff --git a/src/Main.kt b/src/Main.kt
            index 1111111..2222222 100644
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -3,4 +3,5 @@ fun main() {
                 val kept = 1
            -    val removed = 2
            +    val added = 2
            +    val extra = 3
                 val tail = 4
        """.trimIndent().toByteArray()

        val hunks = previewPatch(patch).shouldBeInstanceOf<DiffResult.Computed>().hunks

        hunks.size shouldBe 1
        val hunk = hunks.single()
        hunk.oldStart shouldBe 3
        hunk.oldLineCount shouldBe 4
        hunk.newStart shouldBe 3
        hunk.newLineCount shouldBe 5
        hunk.lines.map { it.type } shouldContainExactly listOf(
            DiffLineType.CONTEXT,
            DiffLineType.DELETED,
            DiffLineType.ADDED,
            DiffLineType.ADDED,
            DiffLineType.CONTEXT,
        )
        hunk.lines.map { it.oldLineNumber } shouldContainExactly listOf(3, 4, null, null, 5)
        hunk.lines.map { it.newLineNumber } shouldContainExactly listOf(3, null, 4, 5, 6)
        hunk.lines[1].content shouldBe "    val removed = 2"
    }

    test("파일 여러 개의 hunk 를 순서대로 모은다") {
        val patch = """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -1 +1 @@
            -a
            +A
            diff --git a/b.kt b/b.kt
            --- a/b.kt
            +++ b/b.kt
            @@ -10,1 +10,2 @@
             keep
            +added
        """.trimIndent().toByteArray()

        val hunks = previewPatch(patch).shouldBeInstanceOf<DiffResult.Computed>().hunks

        hunks.size shouldBe 2
        hunks[0].oldLineCount shouldBe 1
        hunks[1].oldStart shouldBe 10
    }

    test("빈 패치는 변경 없음으로, 계산 실패가 아니라 빈 hunk 목록이다") {
        previewPatch(ByteArray(0)).shouldBeInstanceOf<DiffResult.Computed>().hunks shouldBe emptyList()
    }

    test("binary 패치는 NotComputed(BINARY) 로 알린다") {
        val gitBinary = """
            diff --git a/logo.png b/logo.png
            index 1111111..2222222 100644
            GIT binary patch
            literal 12
        """.trimIndent().toByteArray()
        val textualBinary = "Binary files a/logo.png and b/logo.png differ\n".toByteArray()

        previewPatch(gitBinary).shouldBeInstanceOf<DiffResult.NotComputed>().reason shouldBe
            DiffResult.Reason.BINARY
        previewPatch(textualBinary).shouldBeInstanceOf<DiffResult.NotComputed>().reason shouldBe
            DiffResult.Reason.BINARY
    }

    test("정확히 1 MiB 는 미리보기하고 1 바이트만 넘어도 TOO_LARGE 로 알린다") {
        val header = """
            diff --git a/big.kt b/big.kt
            --- a/big.kt
            +++ b/big.kt
            @@ -1 +1 @@
            -a
            +A

        """.trimIndent().toByteArray()
        val exact = header + ByteArray(MAX_PREVIEW_PATCH_BYTES - header.size) { ' '.code.toByte() }
        val over = exact + ' '.code.toByte()

        exact.size shouldBe MAX_PREVIEW_PATCH_BYTES
        previewPatch(exact).shouldBeInstanceOf<DiffResult.Computed>()
        previewPatch(over).shouldBeInstanceOf<DiffResult.NotComputed>().reason shouldBe
            DiffResult.Reason.TOO_LARGE
    }

    test("format-patch 메타데이터의 작성자·제목을 읽고 일반 diff 에서는 비운다") {
        val formatted = """
            From 1111111111111111111111111111111111111111 Mon Sep 17 00:00:00 2001
            From: 조봉준 <biuea3866@gmail.com>
            Subject: [PATCH 1/2] 패치 화면을 붙인다

            본문 설명
            ---
            diff --git a/a.kt b/a.kt
        """.trimIndent().toByteArray()

        val metadata = patchCommitMetadataOf(formatted)
        metadata.author?.name shouldBe "조봉준"
        metadata.author?.email shouldBe "biuea3866@gmail.com"
        metadata.message shouldBe "패치 화면을 붙인다\n\n본문 설명"

        val plain = patchCommitMetadataOf("diff --git a/a.kt b/a.kt\n".toByteArray())
        plain.author shouldBe null
        plain.message shouldBe null
        plain.isEmpty shouldBe true
    }

    test("제목 한 줄만 있는 패치는 본문 없이 제목을 커밋 메시지로 준다") {
        val formatted = """
            From: A B <a@b.c>
            Subject: 한 줄 제목

            ---
            diff --git a/a.kt b/a.kt
        """.trimIndent().toByteArray()

        patchCommitMetadataOf(formatted).message shouldBe "한 줄 제목"
    }
})
