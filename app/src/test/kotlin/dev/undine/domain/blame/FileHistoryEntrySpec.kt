package dev.undine.domain.blame

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class FileHistoryEntrySpec : FunSpec({

    test("파일 이력 항목은 당시 경로와 rename 이전 경로를 함께 보존한다") {
        val entry = FileHistoryEntry(commit = commitOf("a"), path = "renamed.kt", previousPath = "original.kt")

        entry.path shouldBe "renamed.kt"
        entry.previousPath shouldBe "original.kt"
        entry.isRename shouldBe true
    }

    test("rename 없는 이력 항목은 이전 경로가 없다") {
        FileHistoryEntry(commit = commitOf("b"), path = "same.kt").isRename shouldBe false
    }
})

private fun commitOf(character: String): Commit = Commit(
    id = CommitId.of(character.repeat(40)),
    parents = emptyList(),
    message = "message",
    author = Person("author", "author@example.invalid"),
    committer = Person("author", "author@example.invalid"),
    authoredAt = Instant.EPOCH,
    committedAt = Instant.EPOCH,
)
