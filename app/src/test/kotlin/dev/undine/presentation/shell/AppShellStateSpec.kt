package dev.undine.presentation.shell

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val REPOSITORY = RepositoryPath("/tmp/undine")
private val OTHER_REPOSITORY = RepositoryPath("/tmp/other")
private val OPERABLE = ActiveRepository.Operable(REPOSITORY)
private val OTHER_OPERABLE = ActiveRepository.Operable(OTHER_REPOSITORY)
private val UNAVAILABLE = ActiveRepository.Unavailable(REPOSITORY)
private val COMMIT = CommitId.of("0".repeat(40))
private const val FILE_PATH = "src/main/kotlin/dev/undine/presentation/App.kt"

/**
 * 셸이 보유하는 선택 상태 — 활성 탭의 저장소·커밋·파일 세 값과 그 사이의 정합만 다룬다.
 *
 * **조작 대상과 탭이 가리키는 저장소는 다른 값이다** (UND-83). 하나의 nullable 이 "열린 저장소" 이자
 * "조작 대상" 이자 "화면을 그릴지" 를 겸하면, 경로를 잃은 탭에서 어느 쪽을 골라도 하나가 깨진다.
 */
class AppShellStateSpec : FunSpec({

    test("초기 상태는 아무것도 선택하지 않은 상태다") {
        val state = AppShellState()

        state.selection shouldBe AppShellSelection(ActiveRepository.None, commit = null, filePath = null)
        state.selection.repository.shouldBeNull()
    }

    test("저장소·커밋·파일을 차례로 고르면 선택이 누적된다") {
        val state = AppShellState()

        state.selectActiveRepository(OPERABLE)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selection shouldBe AppShellSelection(OPERABLE, COMMIT, FILE_PATH)
        state.selection.repository shouldBe REPOSITORY
    }

    test("저장소를 바꾸면 이전 저장소의 커밋·파일 선택이 비워진다") {
        val state = AppShellState()
        state.selectActiveRepository(OPERABLE)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectActiveRepository(OTHER_OPERABLE)

        state.selection shouldBe AppShellSelection(OTHER_OPERABLE, null, null)
    }

    test("같은 저장소를 다시 고르면 커밋·파일 선택을 유지한다") {
        val state = AppShellState()
        state.selectActiveRepository(OPERABLE)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectActiveRepository(OPERABLE)

        state.selection shouldBe AppShellSelection(OPERABLE, COMMIT, FILE_PATH)
    }

    test("커밋을 바꾸면 이전 커밋의 파일 선택이 비워진다") {
        val state = AppShellState()
        state.selectActiveRepository(OPERABLE)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectCommit(null)

        state.selection shouldBe AppShellSelection(OPERABLE, null, null)
    }

    test("열린 탭이 없어지면 세 선택이 모두 비워진다") {
        val state = AppShellState()
        state.selectActiveRepository(OPERABLE)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectActiveRepository(ActiveRepository.None)

        state.selection shouldBe AppShellSelection(ActiveRepository.None, null, null)
    }

    test("초기 선택을 받아 만들 수 있다 — 복원 시점의 값을 주입한다") {
        val state = AppShellState(activeRepository = OPERABLE, commit = COMMIT, filePath = FILE_PATH)

        state.selection shouldBe AppShellSelection(OPERABLE, COMMIT, FILE_PATH)
    }

    // 경로를 잃은 탭에서 두 값이 갈린다 — 셸은 그릴 것을 알고(탭 참조) 조작은 갈 곳이 없다.
    test("경로를 잃으면 조작 대상은 사라지고 탭이 가리키는 저장소는 남는다") {
        val state = AppShellState()
        state.selectActiveRepository(OPERABLE)

        state.selectActiveRepository(UNAVAILABLE)

        state.selection.repository.shouldBeNull()
        state.selection.activeRepository shouldBe UNAVAILABLE
        state.selection.activeRepository.referencedPath shouldBe REPOSITORY
    }

    test("경로를 잃으면 그 저장소의 커밋·파일 선택을 비운다 — 읽을 수 없는 대상이다") {
        val state = AppShellState()
        state.selectActiveRepository(OPERABLE)
        state.selectCommit(COMMIT)
        state.selectFile(FILE_PATH)

        state.selectActiveRepository(UNAVAILABLE)

        state.selection shouldBe AppShellSelection(UNAVAILABLE, null, null)
    }

    test("경로가 돌아오면 다시 조작 대상이 된다") {
        val state = AppShellState()
        state.selectActiveRepository(UNAVAILABLE)

        state.selectActiveRepository(OPERABLE)

        state.selection.repository shouldBe REPOSITORY
    }

    test("탭이 없는 상태에는 가리키는 저장소도 없다") {
        ActiveRepository.None.referencedPath.shouldBeNull()
    }
})
