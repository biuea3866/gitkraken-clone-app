package dev.undine.infrastructure.git.history

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private const val MAIN_BRANCH = "main"
private val MAIN_REF = RefName("refs/heads/$MAIN_BRANCH")
private val BASE_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** 겹침을 관측할 수 있을 만큼만 임계 구역에 머무른다. */
private const val CRITICAL_SECTION_MILLIS = 2L

private const val CONCURRENT_CALLERS = 8

private fun newRepository(directory: File): Git {
    val git = Git.init().setDirectory(directory).setInitialBranch(MAIN_BRANCH).call()
    git.repository.config.apply {
        setString("user", null, "name", "Undine Tester")
        setString("user", null, "email", "tester@undine.dev")
        save()
    }
    return git
}

private fun Git.commitFile(fileName: String, at: Instant): RevCommit {
    File(repository.workTree, fileName).writeText("content of $fileName")
    add().addFilepattern(fileName).call()
    val identity = PersonIdent("Undine Tester", "tester@undine.dev", at, ZoneOffset.UTC)
    return commit()
        .setMessage("commit $fileName")
        .setAuthor(identity)
        .setCommitter(identity)
        .call()
}

/** 커밋 시각을 1초씩 뒤로 밀며 [count] 건을 쌓는다 — 순서가 시각에 좌우되지 않게 고정값을 쓴다. */
private fun Git.commitSeries(count: Int): List<RevCommit> =
    (0 until count).map { index -> commitFile("file-$index.txt", BASE_TIME.plusSeconds(index.toLong())) }

/** [RevWalk] 의 닫힘·순회를 관찰하기 위한 테스트 대역. JGit 자체는 실제 구현을 그대로 쓴다. */
private class ObservableRevWalk(repository: Repository) : RevWalk(repository) {
    var closed: Boolean = false
        private set
    var onNext: () -> Unit = {}

    override fun next(): RevCommit? {
        onNext()
        return super.next()
    }

    override fun close() {
        closed = true
        super.close()
    }
}

/**
 * [directory] 의 저장소를 [GitAccess] 로 열어 Gateway 에 넘기고, 블록이 끝나면 핸들을 닫는다.
 * 프로덕션 배선(UND-26)과 같은 경로로 검증하기 위해 `Repository` 를 직접 주입하지 않는다.
 */
private suspend fun <T> withHistoryGateway(
    directory: File,
    openRevWalk: (Repository) -> RevWalk = { repository -> RevWalk(repository) },
    block: suspend (HistoryGatewayImpl) -> T,
): T {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(directory.path)) { }
    return try {
        block(HistoryGatewayImpl(gitAccess, openRevWalk))
    } finally {
        gitAccess.close()
    }
}

class HistoryGatewayImplSpec : FunSpec({

    test("커밋 100건 저장소에서 limit=20 이면 최신 20건만 반환한다") {
        val directory = tempdir()
        val created = newRepository(directory).use { git -> git.commitSeries(count = 100) }

        withHistoryGateway(directory) { gateway ->
            val page = gateway.load(listOf(MAIN_REF), offset = 0, limit = 20)

            page.map { it.id } shouldContainExactly created.takeLast(20).reversed().map { CommitId.of(it.name) }
        }
    }

    test("offset 을 옮겨가며 전부 조회하면 중복·누락 없이 전체 커밋이 나온다") {
        val directory = tempdir()
        val created = newRepository(directory).use { git -> git.commitSeries(count = 100) }

        withHistoryGateway(directory) { gateway ->
            val collected = (0 until 100 step 20).flatMap { offset ->
                gateway.load(listOf(MAIN_REF), offset = offset, limit = 20)
            }

            collected.map { it.id } shouldContainExactly created.reversed().map { CommitId.of(it.name) }
        }
    }

    test("마지막 페이지를 넘어선 offset 은 빈 리스트를 반환한다") {
        val directory = tempdir()
        newRepository(directory).use { git -> git.commitSeries(count = 3) }

        withHistoryGateway(directory) { gateway ->
            gateway.load(listOf(MAIN_REF), offset = 10, limit = 20).shouldBeEmpty()
        }
    }

    test("병합 커밋은 두 부모 ID 를 모두 보존한다") {
        val directory = tempdir()
        val created = newRepository(directory).use { git ->
            val base = git.commitFile("base.txt", BASE_TIME)
            git.checkout().setCreateBranch(true).setName("feature").call()
            val sideCommit = git.commitFile("side.txt", BASE_TIME.plusSeconds(1))
            git.checkout().setName(MAIN_BRANCH).call()
            val mainCommit = git.commitFile("main.txt", BASE_TIME.plusSeconds(2))
            git.merge()
                .include(git.repository.findRef("feature"))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setMessage("merge feature")
                .call()
            listOf(base, sideCommit, mainCommit)
        }
        val (base, sideCommit, mainCommit) = created

        withHistoryGateway(directory) { gateway ->
            val page = gateway.load(listOf(MAIN_REF), offset = 0, limit = 10)

            val mergeCommit = page.first()
            mergeCommit.parents shouldContainExactly listOf(
                CommitId.of(mainCommit.name),
                CommitId.of(sideCommit.name),
            )
            page.map { it.id } shouldContainExactly listOf(
                mergeCommit.id,
                CommitId.of(mainCommit.name),
                CommitId.of(sideCommit.name),
                CommitId.of(base.name),
            )
        }
    }

    test("커밋이 0건인 빈 저장소는 빈 리스트를 반환하고 예외를 던지지 않는다") {
        val directory = tempdir()
        newRepository(directory).close()

        withHistoryGateway(directory) { gateway ->
            gateway.load(emptyList(), offset = 0, limit = 20).shouldBeEmpty()
        }
    }

    test("refs 가 비면 커밋이 있어도 빈 리스트를 반환한다") {
        val directory = tempdir()
        newRepository(directory).use { git -> git.commitSeries(count = 3) }

        withHistoryGateway(directory) { gateway ->
            gateway.load(emptyList(), offset = 0, limit = 20).shouldBeEmpty()
        }
    }

    test("커밋 1건짜리 저장소는 부모 목록이 빈 리스트이고 identity·시각이 그대로 매핑된다") {
        val directory = tempdir()
        val created = newRepository(directory).use { git -> git.commitFile("only.txt", BASE_TIME) }

        withHistoryGateway(directory) { gateway ->
            val page = gateway.load(listOf(MAIN_REF), offset = 0, limit = 20)

            page.size shouldBe 1
            val commit: Commit = page.first()
            commit.id shouldBe CommitId.of(created.name)
            commit.parents.shouldBeEmpty()
            commit.message shouldBe "commit only.txt"
            commit.author.name shouldBe "Undine Tester"
            commit.author.email shouldBe "tester@undine.dev"
            commit.committer.name shouldBe "Undine Tester"
            commit.authoredAt shouldBe BASE_TIME
            commit.committedAt shouldBe BASE_TIME
        }
    }

    test("시각이 부모보다 앞선 자식도 위상 순서상 부모보다 먼저 반환된다") {
        val directory = tempdir()
        val created = newRepository(directory).use { git ->
            val parent = git.commitFile("parent.txt", BASE_TIME.plusSeconds(3600))
            val child = git.commitFile("child.txt", BASE_TIME)
            listOf(parent, child)
        }
        val (parent, child) = created

        withHistoryGateway(directory) { gateway ->
            val page = gateway.load(listOf(MAIN_REF), offset = 0, limit = 20)

            page.map { it.id } shouldContainExactly listOf(
                CommitId.of(child.name),
                CommitId.of(parent.name),
            )
        }
    }

    test("여러 ref 를 시작점으로 주면 양쪽 브랜치의 커밋이 모두 나온다") {
        val directory = tempdir()
        val created = newRepository(directory).use { git ->
            val base = git.commitFile("base.txt", BASE_TIME)
            git.checkout().setCreateBranch(true).setName("feature").call()
            val sideCommit = git.commitFile("side.txt", BASE_TIME.plusSeconds(1))
            git.checkout().setName(MAIN_BRANCH).call()
            val mainCommit = git.commitFile("main.txt", BASE_TIME.plusSeconds(2))
            listOf(base, sideCommit, mainCommit)
        }
        val (base, sideCommit, mainCommit) = created

        withHistoryGateway(directory) { gateway ->
            val page = gateway.load(
                listOf(MAIN_REF, RefName("refs/heads/feature")),
                offset = 0,
                limit = 20,
            )

            page.map { it.id } shouldContainExactly listOf(
                CommitId.of(mainCommit.name),
                CommitId.of(sideCommit.name),
                CommitId.of(base.name),
            )
        }
    }

    test("조회가 끝나면 RevWalk 가 닫혀 핸들이 남지 않는다") {
        val directory = tempdir()
        newRepository(directory).use { git -> git.commitSeries(count = 5) }
        val walks = mutableListOf<ObservableRevWalk>()

        withHistoryGateway(
            directory,
            openRevWalk = { repository -> ObservableRevWalk(repository).also { walks += it } },
        ) { gateway ->
            gateway.load(listOf(MAIN_REF), offset = 0, limit = 2)
            gateway.load(listOf(MAIN_REF), offset = 2, limit = 2)
        }

        walks.size shouldBe 2
        walks.all { it.closed } shouldBe true
    }

    test("동시 호출에도 공유 Repository 접근이 겹치지 않는다") {
        val directory = tempdir()
        newRepository(directory).use { git -> git.commitSeries(count = 5) }
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        withHistoryGateway(
            directory,
            openRevWalk = { repository ->
                ObservableRevWalk(repository).apply {
                    onNext = {
                        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        Thread.sleep(CRITICAL_SECTION_MILLIS)
                        active.decrementAndGet()
                    }
                }
            },
        ) { gateway ->
            coroutineScope {
                repeat(CONCURRENT_CALLERS) {
                    launch(Dispatchers.Default) { gateway.load(listOf(MAIN_REF), offset = 0, limit = 5) }
                }
            }
        }

        maxActive.get() shouldBe 1
    }

    test("존재하지 않는 ref 는 NotFound(REF) 로 번역된다") {
        val directory = tempdir()
        newRepository(directory).use { git -> git.commitSeries(count = 1) }

        withHistoryGateway(directory) { gateway ->
            val failure = shouldThrow<UndineException.NotFound> {
                gateway.load(listOf(RefName("refs/heads/missing")), offset = 0, limit = 20)
            }

            failure.kind shouldBe UndineException.NotFound.Kind.REF
            failure.name shouldBe "refs/heads/missing"
        }
    }

    test("예상 못 한 JGit I/O 실패는 GitOperationFailed 로 번역되고 원인을 보존한다") {
        val directory = tempdir()
        newRepository(directory).use { git -> git.commitSeries(count = 3) }
        val cause = IOException("pack file corrupted")

        withHistoryGateway(
            directory,
            openRevWalk = { repository -> ObservableRevWalk(repository).apply { onNext = { throw cause } } },
        ) { gateway ->
            val failure = shouldThrow<UndineException.GitOperationFailed> {
                gateway.load(listOf(MAIN_REF), offset = 0, limit = 20)
            }

            failure.operation shouldBe "history.load"
            failure.cause shouldBe cause
        }
    }

    test("음수 offset 은 IllegalArgumentException 이다") {
        val directory = tempdir()
        newRepository(directory).close()

        withHistoryGateway(directory) { gateway ->
            shouldThrow<IllegalArgumentException> {
                gateway.load(listOf(MAIN_REF), offset = -1, limit = 20)
            }
        }
    }

    test("0 이하 limit 은 IllegalArgumentException 이다") {
        val directory = tempdir()
        newRepository(directory).close()

        withHistoryGateway(directory) { gateway ->
            shouldThrow<IllegalArgumentException> {
                gateway.load(listOf(MAIN_REF), offset = 0, limit = 0)
            }
        }
    }

    test("순회 중 코루틴이 취소되면 CancellationException 을 삼키지 않는다") {
        val directory = tempdir()
        newRepository(directory).use { git -> git.commitSeries(count = 20) }
        var runningJob: Job? = null

        withHistoryGateway(
            directory,
            openRevWalk = { repository ->
                ObservableRevWalk(repository).apply { onNext = { runningJob?.cancel() } }
            },
        ) { gateway ->
            var result: Result<List<Commit>>? = null
            coroutineScope {
                val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    result = runCatching { gateway.load(listOf(MAIN_REF), offset = 0, limit = 20) }
                }
                runningJob = job
                job.start()
                job.join()
            }

            requireNotNull(result).exceptionOrNull().shouldBeInstanceOf<CancellationException>()
        }
    }
})
