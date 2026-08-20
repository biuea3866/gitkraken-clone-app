package dev.undine.infrastructure.git.remote

import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

private const val MAIN_BRANCH = "main"
private const val ORIGIN = "origin"
private const val MAIN_REF = "refs/heads/$MAIN_BRANCH"
private const val TRACKING_REF = "refs/remotes/$ORIGIN/$MAIN_BRANCH"
private const val BACKUP_STAMP = 1_700_000_000_000L
private const val CONCURRENT_FETCHES = 8
private const val BACKUP_REF = "$FORCE_PUSH_BACKUP_PREFIX/$ORIGIN/$MAIN_BRANCH-$BACKUP_STAMP"
private val NO_PROGRESS: (Progress) -> Unit = { }

/** 원격 작업이 조용히 익명 접근으로 떨어지지 않는지 보려고, 자격증명 제공자는 항상 부재 상태로 둔다. */
private fun gatewayOf(gitAccess: GitAccess, now: () -> Long = { BACKUP_STAMP }): RemoteGatewayImpl =
    RemoteGatewayImpl(
        gitAccess = gitAccess,
        credentialsProvider = GitCredentialHelperProvider { null },
        now = now,
    )

/** 공유 핸들은 [GitAccess] 가 소유한다 — 게이트웨이는 이 경계 안에서만 저장소를 만진다. */
private suspend fun accessTo(directory: File): GitAccess =
    GitAccess().also { access -> access.open(RepositoryPath(directory.absolutePath)) { } }

private fun initRepository(directory: File): Git =
    Git.init().setDirectory(directory).setInitialBranch(MAIN_BRANCH).call()

private fun commit(git: Git, fileName: String, content: String): ObjectId {
    File(git.repository.workTree, fileName).writeText(content)
    git.add().addFilepattern(fileName).call()
    return git.commit()
        .setMessage("add $fileName")
        .setAuthor("Undine", "undine@example.com")
        .setCommitter("Undine", "undine@example.com")
        .call()
        .toObjectId()
}

private fun seedRepository(directory: File): ObjectId =
    initRepository(directory).use { seed -> commit(seed, "a.txt", "first") }

private fun cloneRepository(source: File, target: File, bare: Boolean = false) {
    Git.cloneRepository()
        .setURI(source.absolutePath)
        .setDirectory(target)
        .setBare(bare)
        .call()
        .use { /* 셋업용 핸들 — 여기서 닫는다 */ }
}

private fun resolve(directory: File, ref: String): ObjectId? =
    Git.open(directory).use { git -> git.repository.resolve(ref) }

class RemoteGatewayImplSpec : FunSpec({

    test("로컬 경로 원격을 clone 하면 커밋 이력이 그대로 복제된다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        val cloneDirectory = File(root, "clone")
        val originHead = initRepository(originDirectory).use { origin ->
            commit(origin, "a.txt", "first")
            commit(origin, "b.txt", "second")
        }

        gatewayOf(GitAccess()).clone(
            url = originDirectory.absolutePath,
            into = RepositoryPath(cloneDirectory.absolutePath),
            onProgress = NO_PROGRESS,
        )

        Git.open(cloneDirectory).use { cloned ->
            cloned.log().call().toList().size shouldBe 2
            cloned.repository.resolve(MAIN_REF) shouldBe originHead
        }
    }

    test("fetch 는 원격 참조를 돌려주고 원격 추적 ref 만 갱신한다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        val cloneDirectory = File(root, "clone")
        seedRepository(originDirectory)
        cloneRepository(originDirectory, cloneDirectory)
        val originHead = Git.open(originDirectory).use { origin -> commit(origin, "b.txt", "second") }
        val localHeadBefore = resolve(cloneDirectory, MAIN_REF)
        val access = accessTo(cloneDirectory)

        val refs = gatewayOf(access).fetch(ORIGIN, NO_PROGRESS)

        refs.map { it.name.value } shouldContain MAIN_REF
        refs.map { it.remote }.toSet() shouldBe setOf(ORIGIN)
        access.withRepository { it.resolve(TRACKING_REF) } shouldBe originHead
        access.withRepository { it.resolve(MAIN_REF) } shouldBe localHeadBefore
        access.close()
    }

    test("fetch 를 동시에 호출해도 공유 저장소 접근이 직렬화돼 모두 성공한다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        val cloneDirectory = File(root, "clone")
        seedRepository(originDirectory)
        cloneRepository(originDirectory, cloneDirectory)
        val originHead = Git.open(originDirectory).use { origin -> commit(origin, "b.txt", "second") }
        val access = accessTo(cloneDirectory)
        val gateway = gatewayOf(access)

        coroutineScope {
            repeat(CONCURRENT_FETCHES) {
                launch(Dispatchers.Default) { gateway.fetch(ORIGIN, NO_PROGRESS).shouldNotBeEmpty() }
            }
        }

        access.withRepository { it.resolve(TRACKING_REF) } shouldBe originHead
        access.close()
    }

    test("pull 하면 로컬 브랜치가 원격과 동기화된다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        val cloneDirectory = File(root, "clone")
        seedRepository(originDirectory)
        cloneRepository(originDirectory, cloneDirectory)
        val originHead = Git.open(originDirectory).use { origin -> commit(origin, "b.txt", "second") }
        val access = accessTo(cloneDirectory)

        gatewayOf(access).pull(ORIGIN, NO_PROGRESS)

        access.withRepository { it.resolve(MAIN_REF) } shouldBe originHead
        access.close()
    }

    test("같은 파일을 양쪽에서 고치면 pull 은 Conflict 로 끝난다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        val cloneDirectory = File(root, "clone")
        seedRepository(originDirectory)
        cloneRepository(originDirectory, cloneDirectory)
        Git.open(originDirectory).use { origin -> commit(origin, "a.txt", "origin change") }
        Git.open(cloneDirectory).use { cloned -> commit(cloned, "a.txt", "local change") }
        val access = accessTo(cloneDirectory)

        val failure = shouldThrow<UndineException.Conflict> { gatewayOf(access).pull(ORIGIN, NO_PROGRESS) }

        failure.paths shouldContain "a.txt"
        access.close()
    }

    test("병합 결과가 성공도 충돌 목록도 아니면 pull 은 GitOperationFailed 다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        val cloneDirectory = File(root, "clone")
        seedRepository(originDirectory)
        cloneRepository(originDirectory, cloneDirectory)
        Git.open(originDirectory).use { origin -> commit(origin, "a.txt", "origin change") }
        // rebase 로 설정된 pull 이 멈추면 결과에 병합 충돌 목록이 없다 — 그 경우의 실패 경로다.
        Git.open(cloneDirectory).use { cloned ->
            cloned.repository.config.apply {
                setBoolean("branch", MAIN_BRANCH, "rebase", true)
                save()
            }
            commit(cloned, "a.txt", "local change")
        }
        val access = accessTo(cloneDirectory)

        val failure = shouldThrow<UndineException.GitOperationFailed> {
            gatewayOf(access).pull(ORIGIN, NO_PROGRESS)
        }

        failure.operation shouldBe "remote.pull"
        // 병합 결과를 보고 판정한 실패다 — JGit 예외를 번역한 것이 아니므로 cause 가 없다.
        failure.cause shouldBe null
        access.close()
    }

    test("체크아웃을 막는 커밋되지 않은 변경이 있으면 pull 실패도 마스킹된 GitOperationFailed 다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        val cloneDirectory = File(root, "clone")
        seedRepository(originDirectory)
        cloneRepository(originDirectory, cloneDirectory)
        Git.open(originDirectory).use { origin -> commit(origin, "a.txt", "origin change") }
        File(cloneDirectory, "a.txt").writeText("uncommitted local edit")
        val access = accessTo(cloneDirectory)

        val failure = shouldThrow<UndineException.GitOperationFailed> {
            gatewayOf(access).pull(ORIGIN, NO_PROGRESS)
        }

        failure.operation shouldBe "remote.pull"
        failure.cause.shouldBeInstanceOf<MaskedRemoteCause>()
        access.close()
    }

    test("원격이 없는 저장소에서 fetch 하면 NotFound(REMOTE) 를 반환한다") {
        val root = tempdir()
        val soloDirectory = File(root, "solo")
        initRepository(soloDirectory).use { solo -> commit(solo, "a.txt", "first") }
        val access = accessTo(soloDirectory)

        val failure = shouldThrow<UndineException.NotFound> { gatewayOf(access).fetch(ORIGIN, NO_PROGRESS) }

        failure.kind shouldBe UndineException.NotFound.Kind.REMOTE
        failure.name shouldBe ORIGIN
        access.close()
    }

    test("저장소가 열려 있지 않으면 fetch 는 StateViolation 이다") {
        shouldThrow<UndineException.StateViolation> { gatewayOf(GitAccess()).fetch(ORIGIN, NO_PROGRESS) }
    }

    test("force=false 인 push 의 non-fast-forward 거절은 예외가 아니라 Rejected(NON_FAST_FORWARD) 다") {
        val root = tempdir()
        val originDirectory = File(root, "origin.git")
        val firstDirectory = File(root, "first")
        val secondDirectory = File(root, "second")
        seedRepository(File(root, "seed"))
        cloneRepository(File(root, "seed"), originDirectory, bare = true)
        cloneRepository(originDirectory, firstDirectory)
        cloneRepository(originDirectory, secondDirectory)
        Git.open(firstDirectory).use { first -> commit(first, "b.txt", "second") }
        val firstAccess = accessTo(firstDirectory)
        gatewayOf(firstAccess).push(RefName(MAIN_REF), force = false, onProgress = NO_PROGRESS) shouldBe
            PushResult.Accepted
        val acceptedHead = resolve(originDirectory, MAIN_REF)
        firstAccess.close()

        Git.open(secondDirectory).use { second -> commit(second, "c.txt", "third") }
        val secondAccess = accessTo(secondDirectory)

        gatewayOf(secondAccess).push(RefName(MAIN_REF), force = false, onProgress = NO_PROGRESS) shouldBe
            PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)

        resolve(originDirectory, MAIN_REF) shouldBe acceptedHead
        secondAccess.close()
    }

    test("force=true 인 push 는 덮어쓰기 전에 원격의 기존 tip 을 백업 ref 로 남긴다") {
        val root = tempdir()
        val originDirectory = File(root, "origin.git")
        val firstDirectory = File(root, "first")
        val secondDirectory = File(root, "second")
        seedRepository(File(root, "seed"))
        cloneRepository(File(root, "seed"), originDirectory, bare = true)
        cloneRepository(originDirectory, firstDirectory)
        cloneRepository(originDirectory, secondDirectory)
        Git.open(firstDirectory).use { first -> commit(first, "b.txt", "second") }
        val firstAccess = accessTo(firstDirectory)
        gatewayOf(firstAccess).push(RefName(MAIN_REF), force = false, onProgress = NO_PROGRESS)
        firstAccess.close()
        val overwrittenHead = resolve(originDirectory, MAIN_REF)
        Git.open(secondDirectory).use { second -> commit(second, "c.txt", "third") }
        val secondAccess = accessTo(secondDirectory)

        val forcedHead = secondAccess.withRepository { it.resolve(MAIN_REF) }
        gatewayOf(secondAccess).push(RefName(MAIN_REF), force = true, onProgress = NO_PROGRESS) shouldBe
            PushResult.Accepted

        resolve(originDirectory, MAIN_REF) shouldBe forcedHead
        secondAccess.withRepository { it.resolve(BACKUP_REF) } shouldBe overwrittenHead
        secondAccess.close()
    }

    test("백업 ref 를 만들지 못하면 force push 를 진행하지 않는다") {
        val root = tempdir()
        val originDirectory = File(root, "origin.git")
        val firstDirectory = File(root, "first")
        val secondDirectory = File(root, "second")
        seedRepository(File(root, "seed"))
        cloneRepository(File(root, "seed"), originDirectory, bare = true)
        cloneRepository(originDirectory, firstDirectory)
        cloneRepository(originDirectory, secondDirectory)
        Git.open(firstDirectory).use { first -> commit(first, "b.txt", "second") }
        val firstAccess = accessTo(firstDirectory)
        gatewayOf(firstAccess).push(RefName(MAIN_REF), force = false, onProgress = NO_PROGRESS)
        firstAccess.close()
        val remoteHeadBefore = resolve(originDirectory, MAIN_REF)
        // 백업 ref 이름을 디렉터리로 선점해 백업 생성을 실패시킨다.
        Git.open(secondDirectory).use { second ->
            val head = commit(second, "c.txt", "third")
            second.repository.updateRef("$BACKUP_REF/blocker").apply {
                setNewObjectId(head)
                setForceUpdate(true)
            }.update()
        }
        val secondAccess = accessTo(secondDirectory)

        val failure = shouldThrow<UndineException.GitOperationFailed> {
            gatewayOf(secondAccess).push(RefName(MAIN_REF), force = true, onProgress = NO_PROGRESS)
        }

        failure.operation shouldBe "remote.push.backup"
        resolve(originDirectory, MAIN_REF) shouldBe remoteHeadBefore
        secondAccess.close()
    }

    test("백업한 뒤 원격이 갱신되면 lease 가 어긋나 force push 를 거절한다") {
        val root = tempdir()
        val originDirectory = File(root, "origin.git")
        val firstDirectory = File(root, "first")
        val secondDirectory = File(root, "second")
        val thirdDirectory = File(root, "third")
        seedRepository(File(root, "seed"))
        cloneRepository(File(root, "seed"), originDirectory, bare = true)
        cloneRepository(originDirectory, firstDirectory)
        cloneRepository(originDirectory, secondDirectory)
        cloneRepository(originDirectory, thirdDirectory)
        Git.open(firstDirectory).use { first -> commit(first, "b.txt", "second") }
        val firstAccess = accessTo(firstDirectory)
        gatewayOf(firstAccess).push(RefName(MAIN_REF), force = false, onProgress = NO_PROGRESS)
        firstAccess.close()
        Git.open(secondDirectory).use { second -> commit(second, "c.txt", "third") }
        val secondAccess = accessTo(secondDirectory)

        // 백업 조회가 도는 사이에 다른 클라이언트가 원격을 갱신한다 — lease 없이는 이 갱신이 덮어써진다.
        var interleaved = false
        var thirdHead: ObjectId? = null
        val interleavingPush: (Progress) -> Unit = {
            if (!interleaved) {
                interleaved = true
                Git.open(thirdDirectory).use { third ->
                    thirdHead = commit(third, "d.txt", "fourth")
                    third.push().setRemote(ORIGIN).setForce(true).call()
                }
            }
        }

        val result = gatewayOf(secondAccess).push(RefName(MAIN_REF), force = true, onProgress = interleavingPush)

        interleaved shouldBe true
        result shouldBe PushResult.Rejected(PushResult.RejectReason.REMOTE_REJECTED)
        resolve(originDirectory, MAIN_REF) shouldBe thirdHead
        secondAccess.close()
    }

    test("원격에 없는 새 브랜치는 덮어쓸 이력이 없으므로 백업 없이 push 된다") {
        val root = tempdir()
        val originDirectory = File(root, "origin.git")
        val workDirectory = File(root, "work")
        seedRepository(File(root, "seed"))
        cloneRepository(File(root, "seed"), originDirectory, bare = true)
        cloneRepository(originDirectory, workDirectory)
        Git.open(workDirectory).use { work -> work.branchCreate().setName("feature").call() }
        val access = accessTo(workDirectory)

        gatewayOf(access).push(RefName("refs/heads/feature"), force = true, onProgress = NO_PROGRESS) shouldBe
            PushResult.Accepted

        resolve(originDirectory, "refs/heads/feature") shouldNotBe null
        access.withRepository { it.resolve("$FORCE_PUSH_BACKUP_PREFIX/$ORIGIN/feature-$BACKUP_STAMP") } shouldBe null
        access.close()
    }

    test("non-fast-forward 가 아닌 원격 거절은 Rejected(REMOTE_REJECTED) 다") {
        val root = tempdir()
        val originDirectory = File(root, "origin.git")
        val workDirectory = File(root, "work")
        seedRepository(File(root, "seed"))
        cloneRepository(File(root, "seed"), originDirectory, bare = true)
        cloneRepository(originDirectory, workDirectory)
        // 원격의 ref 잠금을 선점해 fast-forward 인데도 갱신을 거절하게 만든다.
        File(originDirectory, "$MAIN_REF.lock").writeText("")
        Git.open(workDirectory).use { work -> commit(work, "b.txt", "second") }
        val access = accessTo(workDirectory)

        gatewayOf(access).push(RefName(MAIN_REF), force = false, onProgress = NO_PROGRESS) shouldBe
            PushResult.Rejected(PushResult.RejectReason.REMOTE_REJECTED)

        access.close()
    }

    test("업스트림이 없으면 push 는 StateViolation 이다") {
        val root = tempdir()
        val soloDirectory = File(root, "solo")
        initRepository(soloDirectory).use { solo -> commit(solo, "a.txt", "first") }
        val access = accessTo(soloDirectory)

        shouldThrow<UndineException.StateViolation> {
            gatewayOf(access).push(RefName(MAIN_REF), force = false, onProgress = NO_PROGRESS)
        }

        access.close()
    }

    test("진행률 콜백은 0 에서 시작해 단조 증가한다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        initRepository(originDirectory).use { origin ->
            repeat(3) { index -> commit(origin, "file$index.txt", "content $index") }
        }
        val reported = mutableListOf<Progress>()

        gatewayOf(GitAccess()).clone(
            url = originDirectory.absolutePath,
            into = RepositoryPath(File(root, "clone").absolutePath),
            onProgress = { reported += it },
        )

        reported.shouldNotBeEmpty()
        reported.first().completedFraction shouldBe 0.0
        reported.all { it.phase.isNotBlank() } shouldBe true
        reported.zipWithNext().all { (before, after) ->
            after.completedFraction >= before.completedFraction
        } shouldBe true
    }

    test("진행 중 취소하면 CancellationException 이 전파된다") {
        val root = tempdir()
        val originDirectory = File(root, "origin")
        initRepository(originDirectory).use { origin ->
            repeat(5) { index -> commit(origin, "file$index.txt", "content $index") }
        }
        val jobHolder = arrayOfNulls<Job>(1)
        var propagated: Throwable? = null

        coroutineScope {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                try {
                    gatewayOf(GitAccess()).clone(
                        url = originDirectory.absolutePath,
                        into = RepositoryPath(File(root, "clone").absolutePath),
                        onProgress = { jobHolder[0]?.cancel() },
                    )
                } catch (cancellation: CancellationException) {
                    propagated = cancellation
                    throw cancellation
                }
            }
            jobHolder[0] = job
            job.start()
            job.join()
        }

        propagated shouldNotBe null
    }
})
