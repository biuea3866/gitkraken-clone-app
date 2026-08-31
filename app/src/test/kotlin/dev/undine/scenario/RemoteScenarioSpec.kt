package dev.undine.scenario

import dev.undine.domain.PushResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import java.io.File

private const val ORIGIN = "origin"
private const val NOTE = "note.md"

/**
 * 시나리오 5 — 로컬 원격 등록 → push → 다른 클론에서 fetch → 이력 일치.
 *
 * **네트워크를 타지 않는다.** 베어 저장소를 파일 경로로 원격에 등록해 검증한다 — 실제 호스트에 붙는
 * 테스트는 CI 에서 불안정해진다 (`testing` 규칙 2).
 */
class RemoteScenarioSpec : FunSpec({

    test("push 한 커밋을 다른 클론이 fetch 로 받아 이력이 일치한다") {
        val root = tempdir()
        val bare = seedBareRemote(File(root, "origin.git"))
        val work = File(root, "work").also(::mkdirsOrFail)
        seedRepository(work)
        addRemote(work, bare)

        val app = ScenarioApp(work)
        app.open()
        app.writeFile(NOTE, "원격으로 보낸다\n")
        app.stageAndCommit("메모를 올린다", NOTE)

        val pushed = app.pushRemote.execute(mainRef(), force = false) { }
        pushed.result shouldBe PushResult.Accepted

        // 다른 클론이 같은 커밋을 받는다 — 베어 저장소를 통해서만 전달된다.
        val clone = File(root, "clone").also(::mkdirsOrFail)
        Git.cloneRepository().setURI(bare.absolutePath).setDirectory(clone).call().close()

        messagesOf(clone) shouldContain "메모를 올린다"
        messagesOf(clone) shouldContainExactly messagesOf(work)
    }

    test("원격이 앞서 있으면 fetch 가 그 참조를 가져온다") {
        val root = tempdir()
        val bare = seedBareRemote(File(root, "origin.git"))
        val work = File(root, "work").also(::mkdirsOrFail)
        seedRepository(work)
        addRemote(work, bare)

        val app = ScenarioApp(work)
        app.open()
        app.pushRemote.execute(mainRef(), force = false) { }

        // 다른 클론이 커밋을 하나 더 올린다.
        val other = File(root, "other").also(::mkdirsOrFail)
        Git.cloneRepository().setURI(bare.absolutePath).setDirectory(other).call().use { git ->
            File(other, NOTE).writeText("다른 곳에서\n")
            git.add().addFilepattern(NOTE).call()
            git.commit().setMessage("다른 곳의 커밋").setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call()
            git.push().call()
        }

        val refs = app.fetchRemote.execute(ORIGIN) { }

        refs.map { it.name.value }.any { it.contains(MAIN_BRANCH) } shouldBe true
        // fetch 는 원격 추적 참조만 옮긴다 — 로컬 이력은 그대로다.
        messagesOf(work) shouldContainExactly listOf("initial")
    }
})

/**
 * 빈 베어 원격. `setInitialBranch` 를 주는 이유는 베어 기본 HEAD 가 `master` 라서 `main` 만 올린 뒤
 * clone 하면 "Remote branch 'HEAD' not found" 로 실패하기 때문이다.
 */
private fun seedBareRemote(directory: File): File {
    Git.init().setBare(true).setInitialBranch(MAIN_BRANCH).setDirectory(directory).call().close()
    return directory
}

private fun mkdirsOrFail(directory: File) {
    require(directory.mkdirs()) { "임시 디렉토리를 만들지 못했다: $directory" }
}

/**
 * 로컬 베어 저장소를 원격으로 등록하고 **업스트림까지 설정**한다.
 *
 * 등록·업스트림 설정은 앱이 아직 노출하지 않는 축이라 셋업에서 한다. 업스트림이 없으면 `push` 는
 * 상태 위반으로 거부한다 — "어디로 보낼지 모르는 push" 를 추측해 보내지 않는 계약이다.
 */
private fun addRemote(work: File, bare: File) {
    Git.open(work).use { git ->
        git.remoteAdd().setName(ORIGIN).setUri(URIish(bare.absolutePath)).call()
        val config = git.repository.config
        config.setString("branch", MAIN_BRANCH, "remote", ORIGIN)
        config.setString("branch", MAIN_BRANCH, "merge", "refs/heads/$MAIN_BRANCH")
        config.save()
    }
}

private fun messagesOf(work: File): List<String> =
    Git.open(work).use { git -> git.log().call().map { it.fullMessage.trim() }.reversed() }
