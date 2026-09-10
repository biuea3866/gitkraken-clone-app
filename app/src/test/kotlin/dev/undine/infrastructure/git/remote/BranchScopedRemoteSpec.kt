package dev.undine.infrastructure.git.remote

import dev.undine.testsupport.ORIGIN_REMOTE
import dev.undine.application.toolbar.FastForwardBranchUseCase
import dev.undine.application.toolbar.FastForwardOutcome
import dev.undine.application.toolbar.FastForwardRefusal
import dev.undine.application.toolbar.FetchRemoteUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.domain.Branch
import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway
import dev.undine.domain.RemoteRef
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.undo.UndoStack
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.REPOSITORY_SESSION_CLOSED
import dev.undine.infrastructure.git.repository.RepositoryGatewayImpl
import dev.undine.infrastructure.git.repository.commitFile
import dev.undine.infrastructure.git.repository.initBareRepository
import dev.undine.infrastructure.git.repository.initRepository
import dev.undine.testsupport.recorderOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import java.io.File

private const val ORIGIN = "origin"
private const val MAIN = "main"
private const val FEATURE = "feature"
private const val FILE_NAME = "a.txt"
private val NO_PROGRESS: (Progress) -> Unit = { }
private val FETCH_ALL = RefSpec("+refs/heads/*:refs/remotes/$ORIGIN/*")
private val ACCEPTED_PUSH_STATUSES =
    setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE)

/**
 * 지목 push·pull 을 **실제 저장소와 로컬 파일 원격**으로 본다.
 *
 * 이 티켓의 존재 이유가 "체크아웃 없이 하는 것" 이므로 (결정 D1), 모든 지목 조작 뒤
 * **HEAD 와 워킹트리가 그대로인지**를 매번 확인한다. 네트워크 호스트는 쓰지 않는다.
 */
private class BranchRemoteFixture(
    val origin: File,
    val work: Git,
    val gitAccess: GitAccess,
    val refGateway: RefGatewayImpl,
    val remoteGateway: RemoteGatewayImpl,
) {
    val fastForwardBranch: FastForwardBranchUseCase = FastForwardBranchUseCase(
        fetchRemote = FetchRemoteUseCase(remoteGateway),
        refGateway = refGateway,
        operationRecorder = recorderOf(UndoStack()),
        sessionBinding = gitAccess,
    )

    val pushRemote: PushRemoteUseCase = PushRemoteUseCase(remoteGateway, recorderOf(UndoStack()))

    fun headRef(): String? = work.repository.fullBranch

    fun headTarget(): ObjectId? = work.repository.resolve("HEAD")

    fun workTreeText(): String = File(work.repository.workTree, FILE_NAME).readText()

    fun localTargetOf(branch: String): ObjectId? = work.repository.resolve("refs/heads/$branch")

    fun originTargetOf(branch: String): ObjectId? =
        Git.open(origin).use { it.repository.resolve("refs/heads/$branch") }

    fun uncommittedChanges(): Set<String> = work.status().call().uncommittedChanges

    suspend fun branchNamed(name: String): Branch =
        refGateway.listBranches().first { !it.isRemote && it.name.value == name }
}

/**
 * 한 origin 을 공유하는 작업 저장소 둘. 두 저장소 모두 `feature` 가 원격보다 한 커밋 뒤처져 있어
 * **어느 쪽이 옮겨졌는지를 ref 위치로** 판정할 수 있다.
 */
private class TwoRepositoryFixture(
    val origin: File,
    val first: File,
    val second: File,
    val gitAccess: GitAccess,
)

/**
 * fetch 가 끝난 **직후** 저장소를 바꾼다 — 지목 pull 의 네 호출 사이에 실재하는 창을 그 자리에서 연다.
 *
 * 이 창은 화면 층 테스트로는 재현되지 않는다. 화면은 조작을 시작할 때 한 번 묶일 뿐이고, 창은
 * 그 뒤 UseCase 안에서 열리기 때문이다 (결정 D14).
 */
private fun switchingAfterFetch(
    delegate: RemoteGateway,
    switch: suspend () -> Unit,
): RemoteGateway = object : RemoteGateway by delegate {
    override suspend fun fetch(remote: String, onProgress: (Progress) -> Unit): List<RemoteRef> =
        delegate.fetch(remote, onProgress).also { switch() }
}

/** 저장소를 따로 열어 읽는다 — 앱이 쥔 핸들이 아니라 **디스크의 ref** 로 판정하기 위해서다. */
private fun targetOf(directory: File, branch: String): ObjectId? =
    Git.open(directory).use { it.repository.resolve("refs/heads/$branch") }

/** 두 번째 원격 — 지목 push 가 HEAD 가 아닌 대상의 업스트림으로 가는지 가른다. */
private const val OTHER_REMOTE = "backup"

private fun Git.addOrigin(origin: File) {
    remoteAdd().setName(ORIGIN).setUri(URIish(origin.absolutePath)).call()
}

/**
 * 원격에 올리고 **거절을 조용히 넘기지 않는다.** JGit push 는 거절을 예외가 아니라 상태로 주므로,
 * 확인하지 않으면 fixture 가 만들었다고 믿는 원격 상태가 실제와 달라진다.
 */
private fun Git.pushBranch(branch: String) {
    push().setRemote(ORIGIN).setRefSpecs(RefSpec("refs/heads/$branch")).call()
        .flatMap { it.remoteUpdates }
        .forEach { update ->
            check(update.status in ACCEPTED_PUSH_STATUSES) {
                "fixture push 가 거절됐습니다: $branch (${update.status})"
            }
        }
}

/** `branch.<name>.remote`·`merge` 를 직접 쓴다 — 체크아웃 없이 추적 설정을 만들기 위해서다. */
private fun Git.trackOrigin(branch: String) {
    repository.config.apply {
        setString("branch", branch, "remote", ORIGIN)
        setString("branch", branch, "merge", "refs/heads/$branch")
        save()
    }
}

class BranchScopedRemoteSpec : FunSpec({

    val openedRepositories = mutableListOf<Git>()

    afterTest {
        openedRepositories.forEach(Git::close)
        openedRepositories.clear()
    }

    fun open(git: Git): Git = git.also { openedRepositories += it }

    /**
     * `origin`(bare) · `producer`(원격을 앞서 나가게 만드는 쪽) · `work`(검증 대상) 세 저장소.
     * `work` 는 `main` 을 체크아웃한 채이고, `feature` 는 만들기만 하고 체크아웃하지 않는다.
     */
    suspend fun fixture(): BranchRemoteFixture {
        val root = tempdir()
        val origin = File(root, ORIGIN)
        open(initBareRepository(origin))

        val producer = open(initRepository(File(root, "producer")))
        producer.commitFile(FILE_NAME, "1\n", "first")
        producer.addOrigin(origin)
        producer.pushBranch(MAIN)

        val work = open(
            Git.cloneRepository()
                .setURI(origin.absolutePath)
                .setDirectory(File(root, "work"))
                .call(),
        )
        val gitAccess = GitAccess()
        gitAccess.open(RepositoryPath(work.repository.workTree.path)) { }
        return BranchRemoteFixture(
            origin = origin,
            work = work,
            gitAccess = gitAccess,
            refGateway = RefGatewayImpl(gitAccess),
            remoteGateway = RemoteGatewayImpl(gitAccess, GitCredentialHelperProvider { null }),
        )
    }

    /** `producer` 에서 [branch] 를 한 커밋 앞세워 origin 에 올린다. */
    fun advanceOnOrigin(root: File, branch: String, content: String) {
        val producer = open(Git.open(File(root, "producer")))
        producer.fetch().setRemote(ORIGIN).setRefSpecs(FETCH_ALL).call()
        val hasLocal = producer.repository.resolve("refs/heads/$branch") != null
        producer.checkout().setCreateBranch(!hasLocal).setName(branch).call()
        // 원격 tip 위에 쌓는다 — 뒤처진 곳에서 쌓으면 push 가 거절돼 원격이 그대로 남는다.
        producer.repository.resolve("refs/remotes/$ORIGIN/$branch")?.let { tip ->
            producer.reset().setMode(ResetCommand.ResetType.HARD).setRef(tip.name).call()
        }
        producer.commitFile(FILE_NAME, content, "advance $branch")
        producer.pushBranch(branch)
    }

    /**
     * `first` · `second` 를 같은 origin 에서 복제하고 **둘 다** `feature` 를 원격보다 한 커밋
     * 뒤처지게 만든다. 뒤처짐이 같아야 "옮겨진 쪽" 이 곧 "조작된 쪽" 이 된다.
     */
    fun twoRepositories(): TwoRepositoryFixture {
        val root = tempdir()
        val origin = File(root, ORIGIN)
        open(initBareRepository(origin))

        val producer = open(initRepository(File(root, "producer")))
        producer.commitFile(FILE_NAME, "1\n", "first")
        producer.addOrigin(origin)
        producer.pushBranch(MAIN)
        producer.branchCreate().setName(FEATURE).call()
        producer.pushBranch(FEATURE)

        val clones = listOf("first", "second").map { name ->
            val directory = File(root, name)
            open(Git.cloneRepository().setURI(origin.absolutePath).setDirectory(directory).call()).apply {
                branchCreate().setName(FEATURE).setStartPoint("refs/remotes/$ORIGIN/$FEATURE").call()
                trackOrigin(FEATURE)
            }
            directory
        }
        advanceOnOrigin(root, FEATURE, "2\n")
        return TwoRepositoryFixture(origin, clones[0], clones[1], GitAccess())
    }

    test("fetch 뒤 저장소가 바뀌어도 시작한 저장소만 옮기고 바뀐 저장소는 건드리지 않는다") {
        val fixture = twoRepositories()
        // 탭 전환처럼 둘 다 열어 둔 채 활성만 바뀌는 경로다 — first 가 시작 저장소다.
        fixture.gitAccess.withSessions { it.open(RepositoryPath(fixture.second.path)) }
        fixture.gitAccess.withSessions { it.open(RepositoryPath(fixture.first.path)) }
        val stack = UndoStack()
        val refGateway = RefGatewayImpl(fixture.gitAccess)
        val remoteGateway = switchingAfterFetch(
            RemoteGatewayImpl(fixture.gitAccess, GitCredentialHelperProvider { null }),
        ) { fixture.gitAccess.withSessions { it.open(RepositoryPath(fixture.second.path)) } }
        val useCase = FastForwardBranchUseCase(
            fetchRemote = FetchRemoteUseCase(remoteGateway),
            refGateway = refGateway,
            operationRecorder = recorderOf(stack, changeRecordingOrder = fixture.gitAccess),
            sessionBinding = fixture.gitAccess,
        )
        val branch = refGateway.listBranches().first { !it.isRemote && it.name.value == FEATURE }
        val secondBefore = targetOf(fixture.second, FEATURE)

        val outcome = useCase.execute(branch, ORIGIN, NO_PROGRESS)

        outcome.shouldBeInstanceOf<FastForwardOutcome.FastForwarded>()
        targetOf(fixture.first, FEATURE) shouldBe targetOf(fixture.origin, FEATURE)
        // 기록은 first 의 스택에 남는다 — second 를 옮겼다면 그 기록이 엉뚱한 저장소를 가리킨다.
        targetOf(fixture.second, FEATURE) shouldBe secondBefore
        stack.size shouldBe 1
        fixture.gitAccess.close()
    }

    test("시작한 저장소가 시퀀스 도중 닫히면 어느 저장소도 옮기지 않고 사유를 올린다") {
        val fixture = twoRepositories()
        // 사용자가 실제로 저장소를 바꾸는 경로다 — 이 경로는 이전 활성 핸들을 **닫는다**.
        val repositories = RepositoryGatewayImpl(fixture.gitAccess)
        repositories.open(RepositoryPath(fixture.first.path))
        val stack = UndoStack()
        val refGateway = RefGatewayImpl(fixture.gitAccess)
        val remoteGateway = switchingAfterFetch(
            RemoteGatewayImpl(fixture.gitAccess, GitCredentialHelperProvider { null }),
        ) { repositories.open(RepositoryPath(fixture.second.path)) }
        val useCase = FastForwardBranchUseCase(
            fetchRemote = FetchRemoteUseCase(remoteGateway),
            refGateway = refGateway,
            operationRecorder = recorderOf(stack, changeRecordingOrder = fixture.gitAccess),
            sessionBinding = fixture.gitAccess,
        )
        val branch = refGateway.listBranches().first { !it.isRemote && it.name.value == FEATURE }
        val firstBefore = targetOf(fixture.first, FEATURE)
        val secondBefore = targetOf(fixture.second, FEATURE)

        val failure = shouldThrow<UndineException.StateViolation> {
            useCase.execute(branch, ORIGIN, NO_PROGRESS)
        }

        failure.detail shouldBe REPOSITORY_SESSION_CLOSED
        targetOf(fixture.first, FEATURE) shouldBe firstBefore
        targetOf(fixture.second, FEATURE) shouldBe secondBefore
        stack.size shouldBe 0
        fixture.gitAccess.close()
    }

    test("체크아웃하지 않은 브랜치를 지목해 올리면 그 브랜치가 원격에 올라가고 HEAD 는 그대로다") {
        val fixture = fixture()
        // feature 를 잠시 체크아웃해 커밋을 쌓고 main 으로 돌아온다 — 지목 대상은 체크아웃돼 있지 않다.
        fixture.work.checkout().setCreateBranch(true).setName(FEATURE).call()
        fixture.work.commitFile(FILE_NAME, "feature\n", "on feature")
        fixture.work.checkout().setName(MAIN).call()
        fixture.work.trackOrigin(FEATURE)
        val featureTarget = fixture.localTargetOf(FEATURE)

        val outcome = fixture.pushRemote.execute(RefName(FEATURE), ORIGIN_REMOTE, force = false, NO_PROGRESS)

        outcome.result shouldBe PushResult.Accepted
        fixture.originTargetOf(FEATURE) shouldBe featureTarget
        fixture.headRef() shouldBe "refs/heads/$MAIN"
        fixture.uncommittedChanges() shouldBe emptySet()
    }

    test("push 는 받은 원격으로만 나가고 다른 원격은 건드리지 않는다") {
        // 게이트웨이는 원격을 **고르지 않는다** — 확인 문장이 말한 대상이 인자로 들어오고 그대로 쓴다.
        // 어느 원격을 고를지는 화면 가드(pushRemoteOf)의 일이고 BranchRemoteOpsSpec 이 검증한다.
        val fixture = fixture()
        val other = tempdir().also { Git.init().setBare(true).setDirectory(it).call().close() }
        fixture.work.remoteAdd().setName(OTHER_REMOTE).setUri(URIish(other.absolutePath)).call()
        fixture.work.checkout().setCreateBranch(true).setName(FEATURE).call()
        fixture.work.commitFile(FILE_NAME, "feature\n", "on feature")
        fixture.work.checkout().setName(MAIN).call()
        val featureTarget = fixture.localTargetOf(FEATURE)

        val outcome = fixture.pushRemote.execute(RefName(FEATURE), OTHER_REMOTE, force = false, NO_PROGRESS)

        outcome.result shouldBe PushResult.Accepted
        targetOf(other, FEATURE) shouldBe featureTarget
        // 지목하지 않은 원격에는 아무것도 올라가지 않는다.
        fixture.originTargetOf(FEATURE) shouldBe null
    }

    test("빨리 감기가 가능한 브랜치는 원격 위치로 옮겨지고 HEAD·워킹트리는 변하지 않는다") {
        val fixture = fixture()
        val root = fixture.origin.parentFile
        fixture.work.branchCreate().setName(FEATURE).setStartPoint("refs/heads/$MAIN").call()
        fixture.work.trackOrigin(FEATURE)
        advanceOnOrigin(root, FEATURE, "2\n")
        val headBefore = fixture.headTarget()
        val workTreeBefore = fixture.workTreeText()

        val outcome = fixture.fastForwardBranch.execute(fixture.branchNamed(FEATURE), ORIGIN, NO_PROGRESS)

        outcome.shouldBeInstanceOf<FastForwardOutcome.FastForwarded>()
        fixture.localTargetOf(FEATURE) shouldBe fixture.originTargetOf(FEATURE)
        fixture.headRef() shouldBe "refs/heads/$MAIN"
        fixture.headTarget() shouldBe headBefore
        fixture.workTreeText() shouldBe workTreeBefore
        fixture.uncommittedChanges() shouldBe emptySet()
    }

    test("빨리 감기가 불가능하면 브랜치를 옮기지 않고 사유를 돌려준다") {
        val fixture = fixture()
        val root = fixture.origin.parentFile
        // 로컬 feature 는 자기 커밋을 갖고, 원격 feature 는 다른 커밋을 갖는다 — 두 갈래다.
        fixture.work.checkout().setCreateBranch(true).setName(FEATURE).call()
        fixture.work.commitFile("local.txt", "local\n", "local only")
        fixture.work.checkout().setName(MAIN).call()
        fixture.work.trackOrigin(FEATURE)
        advanceOnOrigin(root, FEATURE, "remote\n")
        val localBefore = fixture.localTargetOf(FEATURE)
        val headBefore = fixture.headTarget()

        val outcome = fixture.fastForwardBranch.execute(fixture.branchNamed(FEATURE), ORIGIN, NO_PROGRESS)

        outcome shouldBe FastForwardOutcome.Refused(RefName(FEATURE), FastForwardRefusal.NOT_FAST_FORWARD)
        fixture.localTargetOf(FEATURE) shouldBe localBefore
        fixture.headRef() shouldBe "refs/heads/$MAIN"
        fixture.headTarget() shouldBe headBefore
    }

    test("현재 체크아웃된 브랜치를 지목하면 fetch 도 이동도 하지 않고 거부한다") {
        val fixture = fixture()
        val root = fixture.origin.parentFile
        advanceOnOrigin(root, MAIN, "2\n")
        val localBefore = fixture.localTargetOf(MAIN)

        val outcome = fixture.fastForwardBranch.execute(fixture.branchNamed(MAIN), ORIGIN, NO_PROGRESS)

        outcome shouldBe FastForwardOutcome.Refused(RefName(MAIN), FastForwardRefusal.CURRENT_BRANCH)
        fixture.localTargetOf(MAIN) shouldBe localBefore
    }

    test("추적 설정이 없는 고아 브랜치는 무엇을 받을지 정할 수 없어 거부한다") {
        val fixture = fixture()
        fixture.work.branchCreate().setName("orphan-ish").setStartPoint("refs/heads/$MAIN").call()
        val localBefore = fixture.localTargetOf("orphan-ish")

        val outcome = fixture.fastForwardBranch.execute(fixture.branchNamed("orphan-ish"), ORIGIN, NO_PROGRESS)

        outcome shouldBe FastForwardOutcome.Refused(RefName("orphan-ish"), FastForwardRefusal.NO_UPSTREAM)
        fixture.localTargetOf("orphan-ish") shouldBe localBefore
    }

    test("원격과 같은 위치면 옮기지 않고 이미 최신임을 알린다") {
        val fixture = fixture()
        fixture.work.branchCreate().setName(FEATURE).setStartPoint("refs/heads/$MAIN").call()
        fixture.work.trackOrigin(FEATURE)
        fixture.work.pushBranch(FEATURE)
        val localBefore = fixture.localTargetOf(FEATURE)

        val outcome = fixture.fastForwardBranch.execute(fixture.branchNamed(FEATURE), ORIGIN, NO_PROGRESS)

        outcome shouldBe FastForwardOutcome.AlreadyUpToDate(RefName(FEATURE))
        fixture.localTargetOf(FEATURE) shouldBe localBefore
    }

    test("detached HEAD 에서도 다른 브랜치를 지목해 받을 수 있고 HEAD 는 그 커밋에 머문다") {
        val fixture = fixture()
        val root = fixture.origin.parentFile
        fixture.work.branchCreate().setName(FEATURE).setStartPoint("refs/heads/$MAIN").call()
        fixture.work.trackOrigin(FEATURE)
        advanceOnOrigin(root, FEATURE, "2\n")
        val detachedAt = requireNotNull(fixture.localTargetOf(MAIN))
        fixture.work.checkout().setName(detachedAt.name).call()

        val outcome = fixture.fastForwardBranch.execute(fixture.branchNamed(FEATURE), ORIGIN, NO_PROGRESS)

        outcome.shouldBeInstanceOf<FastForwardOutcome.FastForwarded>()
        fixture.headTarget() shouldBe detachedAt
        fixture.work.repository.fullBranch shouldBe detachedAt.name
    }

    test("화면 스냅샷이 낡았어도 기대 위치는 조회한 최신 값을 쓴다") {
        val fixture = fixture()
        val root = fixture.origin.parentFile
        fixture.work.branchCreate().setName(FEATURE).setStartPoint("refs/heads/$MAIN").call()
        fixture.work.trackOrigin(FEATURE)
        val staleSnapshot = fixture.branchNamed(FEATURE)
        // 스냅샷을 뜬 뒤 로컬 feature 가 다른 곳으로 움직인다.
        fixture.work.checkout().setName(FEATURE).call()
        fixture.work.commitFile(FILE_NAME, "moved\n", "moved after snapshot")
        fixture.work.checkout().setName(MAIN).call()
        val movedTo = fixture.localTargetOf(FEATURE)
        fixture.work.pushBranch(FEATURE)
        advanceOnOrigin(root, FEATURE, "further\n")

        val outcome = fixture.fastForwardBranch.execute(staleSnapshot, ORIGIN, NO_PROGRESS)

        // 낡은 스냅샷의 target 을 expected 로 썼다면 조건부 갱신이 거부됐을 자리다.
        outcome.shouldBeInstanceOf<FastForwardOutcome.FastForwarded>()
        fixture.localTargetOf(FEATURE) shouldBe fixture.originTargetOf(FEATURE)
        (fixture.localTargetOf(FEATURE) == movedTo) shouldBe false
    }

    test("커밋이 하나도 없는 저장소에서는 지목 브랜치를 찾지 못했다고 알린다") {
        val root = tempdir()
        val empty = open(initRepository(File(root, "empty")))
        val gitAccess = GitAccess()
        gitAccess.open(RepositoryPath(empty.repository.workTree.path)) { }
        val refGateway = RefGatewayImpl(gitAccess)
        val remoteGateway = RemoteGatewayImpl(gitAccess, GitCredentialHelperProvider { null })
        val useCase = FastForwardBranchUseCase(
            fetchRemote = FetchRemoteUseCase(remoteGateway),
            refGateway = refGateway,
            operationRecorder = recorderOf(UndoStack()),
            sessionBinding = gitAccess,
        )
        empty.addOrigin(File(root, ORIGIN).also { initBareRepository(it).use { } })
        val absent = Branch(
            name = RefName(FEATURE),
            target = dev.undine.domain.CommitId.of("1".repeat(40)),
            isCurrent = false,
            isRemote = false,
            upstream = RefName("origin/$FEATURE"),
            ahead = 0,
            behind = 0,
        )

        shouldThrow<UndineException.NotFound> { useCase.execute(absent, ORIGIN, NO_PROGRESS) }
    }
})
