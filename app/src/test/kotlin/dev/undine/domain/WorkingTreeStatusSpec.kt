package dev.undine.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val SAMPLE_CHANGE = FileChange(
    path = "src/main/kotlin/dev/undine/domain/Commit.kt",
    previousPath = null,
    changeType = ChangeType.MODIFIED,
    addedLines = 3,
    deletedLines = 1,
    isBinary = false,
)

private val EMPTY_STATUS = WorkingTreeStatus(
    staged = emptyList(),
    unstaged = emptyList(),
    untracked = emptyList(),
    conflicted = emptyList(),
)

class WorkingTreeStatusSpec : FunSpec({

    test("네 목록이 모두 비었을 때만 isClean 이 참이다") {
        EMPTY_STATUS.isClean shouldBe true
    }

    test("staged 에 변경이 있으면 isClean 이 거짓이다") {
        EMPTY_STATUS.copy(staged = listOf(SAMPLE_CHANGE)).isClean shouldBe false
    }

    test("unstaged 에 변경이 있으면 isClean 이 거짓이다") {
        EMPTY_STATUS.copy(unstaged = listOf(SAMPLE_CHANGE)).isClean shouldBe false
    }

    test("untracked 파일이 있으면 isClean 이 거짓이다") {
        EMPTY_STATUS.copy(untracked = listOf("build.gradle.kts")).isClean shouldBe false
    }

    test("conflicted 파일이 있으면 isClean 이 거짓이다") {
        EMPTY_STATUS.copy(conflicted = listOf("README.md")).isClean shouldBe false
    }

    test("isClean 은 파생 프로퍼티라 equals 비교에 참여하지 않는다") {
        val dirty = EMPTY_STATUS.copy(untracked = listOf("README.md"))
        dirty.copy(untracked = emptyList()) shouldBe EMPTY_STATUS
    }
})
