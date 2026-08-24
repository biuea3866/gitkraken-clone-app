package dev.undine.domain.search

import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.FileChange
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import dev.undine.domain.UndineException
import java.time.Instant
import java.time.LocalDate

private val TARGET_HASH = hashOf("3f2a9c")

private fun fileChange(path: String) = FileChange(
    path = path,
    previousPath = null,
    changeType = ChangeType.MODIFIED,
    addedLines = 1,
    deletedLines = 0,
    isBinary = false,
)

/** 커밋 검색 조건 — 메시지·작성자·해시·기간·파일 경로의 순수 판정 규칙. */
class CommitSearchCriteriaSpec : FunSpec({

    test("메시지 부분 일치는 대소문자를 무시한다") {
        val commit = commitOf(id = TARGET_HASH, message = "Fix Login Timeout")

        CommitSearchCriteria(message = "login").matchesMetadata(commit) shouldBe true
        CommitSearchCriteria(message = "LOGIN").matchesMetadata(commit) shouldBe true
        CommitSearchCriteria(message = "logout").matchesMetadata(commit) shouldBe false
    }

    test("작성자는 이름 일부로도 이메일 일부로도 검색된다") {
        val commit = commitOf(
            id = TARGET_HASH,
            authorName = "Hong Gildong",
            authorEmail = "gildong@undine.dev",
        )

        CommitSearchCriteria(author = "gildong").matchesMetadata(commit) shouldBe true
        CommitSearchCriteria(author = "HONG").matchesMetadata(commit) shouldBe true
        CommitSearchCriteria(author = "@undine.dev").matchesMetadata(commit) shouldBe true
        CommitSearchCriteria(author = "someone-else").matchesMetadata(commit) shouldBe false
    }

    test("짧은 해시 접두사로 커밋이 검색된다") {
        val commit = commitOf(id = TARGET_HASH)

        CommitSearchCriteria(hashPrefix = "3f2a").matchesMetadata(commit) shouldBe true
        CommitSearchCriteria(hashPrefix = "3F2A").matchesMetadata(commit) shouldBe true
        CommitSearchCriteria(hashPrefix = TARGET_HASH).matchesMetadata(commit) shouldBe true
        CommitSearchCriteria(hashPrefix = "9999").matchesMetadata(commit) shouldBe false
    }

    test("짧은 해시 판정은 CommitId.of 를 거치지 않아 예외를 내지 않는다") {
        // CommitId.of 에 짧은 값을 넘기면 실패한다 — 조건 판정이 그 경로를 쓰지 않음을 고정한다.
        shouldThrow<UndineException.InvalidCommitId> { CommitId.of("3f2a") }

        CommitSearchCriteria(hashPrefix = "3f2a").matchesMetadata(commitOf(id = TARGET_HASH)) shouldBe true
    }

    test("기간 필터는 시작일과 종료일 당일 커밋을 포함한다") {
        val start = LocalDate.of(2026, 3, 10)
        val end = LocalDate.of(2026, 3, 12)
        val criteria = CommitSearchCriteria(since = start, until = end, zone = SEARCH_ZONE)

        val onStart = commitOf(id = TARGET_HASH, committedAt = Instant.parse("2026-03-10T00:00:00Z"))
        val onEnd = commitOf(id = TARGET_HASH, committedAt = Instant.parse("2026-03-12T23:59:59Z"))
        val beforeStart = commitOf(id = TARGET_HASH, committedAt = Instant.parse("2026-03-09T23:59:59Z"))
        val afterEnd = commitOf(id = TARGET_HASH, committedAt = Instant.parse("2026-03-13T00:00:00Z"))

        criteria.matchesMetadata(onStart) shouldBe true
        criteria.matchesMetadata(onEnd) shouldBe true
        criteria.matchesMetadata(beforeStart) shouldBe false
        criteria.matchesMetadata(afterEnd) shouldBe false
    }

    test("기간 판정 기준은 authoredAt 이 아니라 committedAt 이다") {
        val criteria = CommitSearchCriteria(
            since = LocalDate.of(2026, 3, 10),
            until = LocalDate.of(2026, 3, 10),
            zone = SEARCH_ZONE,
        )
        val commit = commitOf(id = TARGET_HASH, committedAt = Instant.parse("2026-03-10T05:00:00Z"))
            .copy(authoredAt = Instant.parse("2026-01-01T00:00:00Z"))

        criteria.matchesMetadata(commit) shouldBe true
    }

    test("여러 조건은 AND 로 결합된다") {
        val commit = commitOf(
            id = TARGET_HASH,
            message = "fix login",
            authorName = "Hong Gildong",
            committedAt = Instant.parse("2026-03-10T12:00:00Z"),
        )

        CommitSearchCriteria(
            message = "login",
            author = "hong",
            hashPrefix = "3f2a",
            since = LocalDate.of(2026, 3, 10),
            zone = SEARCH_ZONE,
        ).matchesMetadata(commit) shouldBe true

        CommitSearchCriteria(message = "login", author = "someone-else")
            .matchesMetadata(commit) shouldBe false
    }

    test("파일 경로 필터는 경로 부분 일치이며 대소문자를 구분한다") {
        val criteria = CommitSearchCriteria(filePath = "src/main")
        val changes = listOf(fileChange("src/main/kotlin/App.kt"), fileChange("README.md"))

        criteria.requiresFileChanges shouldBe true
        criteria.matchesChangedFiles(changes) shouldBe true
        criteria.matchesChangedFiles(listOf(fileChange("README.md"))) shouldBe false
        CommitSearchCriteria(filePath = "SRC/MAIN").matchesChangedFiles(changes) shouldBe false
    }

    test("경로 조건이 없으면 변경 파일을 보지 않고 통과시킨다") {
        val criteria = CommitSearchCriteria(message = "login")

        criteria.requiresFileChanges shouldBe false
        criteria.matchesChangedFiles(emptyList()) shouldBe true
    }

    test("조건이 하나도 없으면 비어 있는 조건이다") {
        CommitSearchCriteria().isEmpty shouldBe true
        CommitSearchCriteria(message = "   ").isEmpty shouldBe true
        CommitSearchCriteria(message = "login").isEmpty shouldBe false
        CommitSearchCriteria(since = LocalDate.of(2026, 3, 10)).isEmpty shouldBe false
    }
})
