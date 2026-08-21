package dev.undine.presentation.shell

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val REPOSITORY = RepositoryPath("/tmp/undine")
private val OTHER_REPOSITORY = RepositoryPath("/tmp/other")
private val COMMIT = CommitId.of("0".repeat(40))
private const val FILE_PATH = "src/main/kotlin/dev/undine/presentation/App.kt"

/** 셸이 보유하는 선택 상태 — 저장소·커밋·파일 세 값과 그 사이의 정합만 다룬다. */
class AppShellStateSpec : FunSpec({

    test("초기 상태는 아무것도 선택하지 않은 상태다") {
        val state = AppShellState()

        state.selection shouldBe AppShellSelection(repository = null, commit = null, filePath = null)
    }

    test("저장소·커밋·파일을 차례로 고르면 선택이 누적된다") {
        val state = AppShellState()

        state.selectRepository(REPOSITORY)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selection shouldBe AppShellSelection(REPOSITORY, COMMIT, FILE_PATH)
    }

    test("저장소를 바꾸면 이전 저장소의 커밋·파일 선택이 비워진다") {
        val state = AppShellState()
        state.selectRepository(REPOSITORY)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectRepository(OTHER_REPOSITORY)

        state.selection shouldBe AppShellSelection(OTHER_REPOSITORY, null, null)
    }

    test("같은 저장소를 다시 고르면 커밋·파일 선택을 유지한다") {
        val state = AppShellState()
        state.selectRepository(REPOSITORY)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectRepository(REPOSITORY)

        state.selection shouldBe AppShellSelection(REPOSITORY, COMMIT, FILE_PATH)
    }

    test("커밋을 바꾸면 이전 커밋의 파일 선택이 비워진다") {
        val state = AppShellState()
        state.selectRepository(REPOSITORY)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectCommit(null)

        state.selection shouldBe AppShellSelection(REPOSITORY, null, null)
    }

    test("저장소를 닫으면 세 선택이 모두 비워진다") {
        val state = AppShellState()
        state.selectRepository(REPOSITORY)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectRepository(null)

        state.selection shouldBe AppShellSelection(null, null, null)
    }

    test("초기 선택을 받아 만들 수 있다 — 복원 시점의 값을 주입한다") {
        val state = AppShellState(repository = REPOSITORY, commit = COMMIT, filePath = FILE_PATH)

        state.selection shouldBe AppShellSelection(REPOSITORY, COMMIT, FILE_PATH)
    }
})
