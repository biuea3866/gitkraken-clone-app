package dev.undine.scenario2

import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleState
import dev.undine.domain.undo.GitOperationKind
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import java.io.File

private const val SUBMODULE_PATH = "lib"
private const val CHILD_FILE = "lib.txt"
private const val PARENT_FILE = "README.md"

private val NOT_INITIALIZED =
    SubmoduleState(initialized = false, locallyModified = false, divergedFromRecorded = false)
private val UP_TO_DATE =
    SubmoduleState(initialized = true, locallyModified = false, divergedFromRecorded = false)
private val LOCALLY_MODIFIED =
    SubmoduleState(initialized = true, locallyModified = true, divergedFromRecorded = false)
private val DIVERGED =
    SubmoduleState(initialized = true, locallyModified = false, divergedFromRecorded = true)

/**
 * 2차 시나리오 7 — 서브모듈 상태가 미초기화 → 최신 → 수정됨 → 어긋남으로 전이한다.
 *
 * 세 축(초기화·로컬 변경·기록과의 어긋남)이 독립이라, 전이를 하나의 enum 으로 접지 않고 **조합**으로
 * 확인한다. clone 직후의 미초기화가 시작점이다 — 그것이 사용자가 실제로 만나는 첫 상태다.
 */
class SubmoduleStateScenario2Spec : FunSpec({

    test("서브모듈 상태가 미초기화 → 최신 → 수정됨 → 어긋남으로 전이한다") {
        val parentClone = cloneWithUninitializedSubmodule()
        scenario2AppAt(parentClone).use { app ->
            app.open()

            // ① 미초기화 — clone 직후에는 gitlink 만 있고 작업 디렉터리가 비어 있다.
            app.singleSubmodule().state shouldBe NOT_INITIALIZED

            // ② 최신 — 초기화하면 부모가 기록한 커밋으로 체크아웃된다.
            app.initializeSubmodule.execute(SUBMODULE_PATH)
            app.singleSubmodule().state shouldBe UP_TO_DATE
            // 되돌릴 수 없는 변경이라도 이력에는 남는다 — 남기지 않으면 Undo 가 이 연산을 건너뛴다.
            app.undoStack.history().first().operation shouldBe GitOperationKind.SUBMODULE_INIT

            // ③ 수정됨 — 서브모듈 워킹트리에 커밋되지 않은 변경이 생겼다.
            File(File(parentClone, SUBMODULE_PATH), CHILD_FILE).writeText("서브모듈에서 고친 내용\n")
            app.singleSubmodule().state shouldBe LOCALLY_MODIFIED

            // ④ 어긋남 — 그 변경을 서브모듈에 커밋하면 부모가 기록한 커밋과 HEAD 가 달라진다.
            commitInsideSubmodule(parentClone)
            app.singleSubmodule().state shouldBe DIVERGED

            // 다시 부모 기록으로 맞추면 최신으로 돌아온다 — 전이가 한 방향으로만 가지 않는다.
            app.updateSubmodule.execute(SUBMODULE_PATH)
            app.singleSubmodule().state shouldBe UP_TO_DATE
        }
    }
})

private suspend fun Scenario2App.singleSubmodule(): Submodule {
    val submodules = loadSubmodules.execute()
    check(submodules.size == 1) { "서브모듈이 정확히 하나여야 한다: $submodules" }
    return submodules.single().also { it.path shouldBe SUBMODULE_PATH }
}

/** 서브모듈이 붙은 부모의 clone — 인덱스에는 gitlink 가 있으나 아직 받아오지 않은 정상 상태다. */
private fun TestConfiguration.cloneWithUninitializedSubmodule(): File {
    val origin = seedRepository(File(tempdir(), "origin"), CHILD_FILE, "라이브러리 최초 내용\n")
    val parent = seedRepository(File(tempdir(), "parent"), PARENT_FILE, "부모 최초 내용\n")
    Git.open(parent).use { git ->
        // 반환된 `Repository` 는 **`use {}` 로** 닫는다 — 직접 `close()` 는 사이에 예외가 나면
        // 건너뛴다 (`.agent/rules/jgit-usage.md`).
        git.submoduleAdd().setPath(SUBMODULE_PATH).setURI(origin.absolutePath).call()?.use { }
        git.add().addFilepattern(".gitmodules").call()
        git.commit()
            .setMessage("서브모듈을 붙인다")
            .setAuthor(FIXED_IDENT)
            .setCommitter(FIXED_IDENT)
            .call()
    }

    val clone = File(tempdir(), "clone")
    Git.cloneRepository().setURI(parent.absolutePath).setDirectory(clone).call().use { git ->
        git.configureLocalIdentity()
    }
    return clone
}

/** 서브모듈 안에서만 커밋한다 — 부모의 gitlink 는 그대로라 HEAD 가 기록과 어긋난다. */
private fun commitInsideSubmodule(parentClone: File) {
    val submoduleWork = File(parentClone, SUBMODULE_PATH)
    Git.open(submoduleWork).use { git ->
        git.configureLocalIdentity()
        git.commitFile(submoduleWork, CHILD_FILE, "서브모듈에서 고친 내용\n", "서브모듈 자체 커밋")
    }
}
