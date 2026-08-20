package dev.undine.infrastructure.git.worktreeops

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import dev.undine.domain.ResetMode
import dev.undine.domain.RevertResult
import dev.undine.domain.UndineException
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryHolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

private const val FILE_NAME = "file.txt"
private const val UNTRACKED_FILE_NAME = "untracked.txt"
private const val ADDED_FILE_NAME = "added.txt"
private const val MISSING_COMMIT_HASH = "0123456789abcdef0123456789abcdef01234567"

/** 커밋 시각을 고정한다 — 테스트는 현재 시각에 의존하지 않는다. */
private val FIXED_IDENT = PersonIdent(
    "Undine Test",
    "test@undine.dev",
    Instant.ofEpochSecond(1_700_000_000L),
    ZoneOffset.UTC,
)

/**
 * 임시 디렉토리에 만든 **실제** JGit 저장소. JGit 을 Mock 으로 대체하지 않는다.
 *
 * 저장소 핸들은 **하나**다 — 테스트 준비용 [Git] 과 [gateway] 가 같은 [Repository] 를 공유하도록
 * [RepositoryHolder] 에 그 핸들을 그대로 돌려준다. 핸들은 [close] 로 반드시 닫는다.
 */
private class RepositoryFixture(private val workDirectory: File) : AutoCloseable {

    private val git: Git = Git.init().setDirectory(workDirectory).call()
    val repository: Repository = git.repository
    private val gitAccess = GitAccess(RepositoryHolder { repository })
    val gateway: WorktreeOpsGateway = WorktreeOpsGatewayImpl(gitAccess)

    init {
        repository.config.apply {
            setString("user", null, "name", FIXED_IDENT.name)
            setString("user", null, "email", FIXED_IDENT.emailAddress)
            save()
        }
    }

    /** [GitAccess] 는 열린 저장소가 있어야 동작한다 — 테스트 저장소를 한 번 연다. */
    suspend fun open() {
        gitAccess.open(RepositoryPath(workDirectory.path)) { }
    }

    fun write(name: String, content: String) = File(workDirectory, name).writeText(content)

    fun read(name: String): String = File(workDirectory, name).readText()

    fun exists(name: String): Boolean = File(workDirectory, name).exists()

    fun commit(name: String, content: String, message: String): CommitId {
        write(name, content)
        git.add().addFilepattern(name).call()
        val commit = git.commit()
            .setMessage(message)
            .setAuthor(FIXED_IDENT)
            .setCommitter(FIXED_IDENT)
            .call()
        return CommitId.of(commit.name)
    }

    fun status() = git.status().call()

    fun headHash(): String = repository.resolve("HEAD").name

    /** revert 진행 중 판정 기준 — `.git/REVERT_HEAD` 존재. */
    fun revertInProgress(): Boolean = File(repository.directory, "REVERT_HEAD").exists()

    fun committerInstantOf(commit: CommitId): Instant =
        RevWalk(repository).use { walk ->
            walk.parseCommit(ObjectId.fromString(commit.value)).committerIdent.whenAsInstant
        }

    override fun close() = git.close()
}

private suspend fun openRepositoryFixture(directory: File): RepositoryFixture =
    RepositoryFixture(directory).also { it.open() }

class WorktreeOpsGatewayImplSpec : FunSpec({

    test("stash push 후 워킹트리가 깨끗해지고 pop 하면 변경이 복원된다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            fixture.write(FILE_NAME, "modified\n")

            val entry = fixture.gateway.stashPush(includeUntracked = false)

            entry.index shouldBe 0
            fixture.read(FILE_NAME) shouldBe "base\n"
            fixture.status().isClean shouldBe true

            fixture.gateway.stashPop()

            fixture.read(FILE_NAME) shouldBe "modified\n"
            fixture.gateway.stashList().shouldBeEmpty()
        }
    }

    test("추적되지 않는 파일은 기본 stash 에 포함되지 않는다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            fixture.write(FILE_NAME, "modified\n")
            fixture.write(UNTRACKED_FILE_NAME, "untracked\n")

            val entry = fixture.gateway.stashPush(includeUntracked = false)

            entry.includedUntracked shouldBe false
            fixture.exists(UNTRACKED_FILE_NAME) shouldBe true
        }
    }

    test("includeUntracked=true 로 stash 하면 추적되지 않는 파일도 포함되고 includedUntracked 가 true 다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            fixture.write(FILE_NAME, "modified\n")
            fixture.write(UNTRACKED_FILE_NAME, "untracked\n")

            val entry = fixture.gateway.stashPush(includeUntracked = true)

            entry.includedUntracked shouldBe true
            fixture.exists(UNTRACKED_FILE_NAME) shouldBe false

            fixture.gateway.stashPop()

            fixture.exists(UNTRACKED_FILE_NAME) shouldBe true
        }
    }

    test("stash 목록은 최신을 index 0 으로 두고 메시지·target·생성 시각을 매핑한다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            fixture.write(FILE_NAME, "first\n")
            val first = fixture.gateway.stashPush(includeUntracked = false)
            fixture.write(FILE_NAME, "second\n")
            val second = fixture.gateway.stashPush(includeUntracked = false)

            val entries = fixture.gateway.stashList()

            entries shouldHaveSize 2
            entries[0].index shouldBe 0
            entries[0].target shouldBe second.target
            entries[1].index shouldBe 1
            entries[1].target shouldBe first.target
            entries[0].message.startsWith("WIP on master:") shouldBe true
            entries[0].message.contains("최초 커밋") shouldBe true
            entries[0].createdAt shouldBe fixture.committerInstantOf(second.target)
        }
    }

    test("stale 해진 index 대신 target 커밋으로 stash 를 적용한다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            fixture.write(FILE_NAME, "first\n")
            val first = fixture.gateway.stashPush(includeUntracked = false)
            fixture.write(FILE_NAME, "second\n")
            fixture.gateway.stashPush(includeUntracked = false)

            first.index shouldBe 0
            fixture.gateway.stashList()[1].target shouldBe first.target

            fixture.gateway.stashApply(first)

            fixture.read(FILE_NAME) shouldBe "first\n"
        }
    }

    test("target 커밋이 사라진 stash 를 다루면 NotFound(STASH, target) 를 던진다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            fixture.write(FILE_NAME, "modified\n")
            val entry = fixture.gateway.stashPush(includeUntracked = false)

            fixture.gateway.stashDrop(entry)

            val applyFailure = shouldThrow<UndineException.NotFound> { fixture.gateway.stashApply(entry) }
            applyFailure.kind shouldBe UndineException.NotFound.Kind.STASH
            applyFailure.name shouldBe entry.target.value

            val dropFailure = shouldThrow<UndineException.NotFound> { fixture.gateway.stashDrop(entry) }
            dropFailure.name shouldBe entry.target.value
        }
    }

    test("stashDrop 은 target 항목만 제거한다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            fixture.write(FILE_NAME, "first\n")
            val first = fixture.gateway.stashPush(includeUntracked = false)
            fixture.write(FILE_NAME, "second\n")
            val second = fixture.gateway.stashPush(includeUntracked = false)

            fixture.gateway.stashDrop(first)

            val remaining = fixture.gateway.stashList()
            remaining shouldHaveSize 1
            remaining[0].target shouldBe second.target
        }
    }

    test("stash 가 0건일 때 pop 하면 NotFound(STASH, 빈 이름) 를 던진다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")

            val thrown = shouldThrow<UndineException.NotFound> { fixture.gateway.stashPop() }

            thrown.kind shouldBe UndineException.NotFound.Kind.STASH
            thrown.name shouldBe ""
        }
    }

    test("변경이 없으면 stash push 는 StateViolation 을 던진다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")

            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.stashPush(includeUntracked = false)
            }
        }
    }

    test("revert 성공은 Succeeded(새 커밋) 이고 변경이 되돌려진다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            val target = fixture.commit(ADDED_FILE_NAME, "added\n", "파일 추가")

            val result = fixture.gateway.revert(target)

            val succeeded = result.shouldBeInstanceOf<RevertResult.Succeeded>()
            succeeded.commit.value shouldBe fixture.headHash()
            fixture.exists(ADDED_FILE_NAME) shouldBe false
            fixture.revertInProgress() shouldBe false
        }
    }

    test("revert 충돌은 예외가 아니라 Conflicted(paths) 이고 저장소는 revert 진행 중으로 남는다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "line1\nline2\nline3\n", "최초 커밋")
            val target = fixture.commit(FILE_NAME, "line1\nchanged\nline3\n", "line2 변경")
            fixture.commit(FILE_NAME, "line1\nchanged again\nline3\n", "line2 재변경")

            val result = fixture.gateway.revert(target)

            val conflicted = result.shouldBeInstanceOf<RevertResult.Conflicted>()
            conflicted.paths shouldContain FILE_NAME
            fixture.revertInProgress() shouldBe true
        }
    }

    test("워킹트리가 더러워 revert 를 적용할 수 없으면 DirtyWorkingTree 를 던진다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")
            val target = fixture.commit(FILE_NAME, "second\n", "두번째 커밋")
            fixture.write(FILE_NAME, "uncommitted local edit\n")

            val thrown = shouldThrow<UndineException.DirtyWorkingTree> { fixture.gateway.revert(target) }

            thrown.paths shouldContain FILE_NAME
        }
    }

    test("soft reset 후 워킹트리와 인덱스 내용이 보존된다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            val first = fixture.commit(FILE_NAME, "first\n", "최초 커밋")
            fixture.commit(FILE_NAME, "second\n", "두번째 커밋")

            fixture.gateway.reset(first, ResetMode.SOFT)

            fixture.headHash() shouldBe first.value
            fixture.read(FILE_NAME) shouldBe "second\n"
            fixture.status().changed shouldContain FILE_NAME
        }
    }

    test("mixed reset 은 인덱스만 되돌리고 워킹트리를 보존한다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            val first = fixture.commit(FILE_NAME, "first\n", "최초 커밋")
            fixture.commit(FILE_NAME, "second\n", "두번째 커밋")

            fixture.gateway.reset(first, ResetMode.MIXED)

            fixture.headHash() shouldBe first.value
            fixture.read(FILE_NAME) shouldBe "second\n"
            fixture.status().modified shouldContain FILE_NAME
        }
    }

    test("hardReset 은 워킹트리를 대상 커밋 상태로 되돌린다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            val first = fixture.commit(FILE_NAME, "first\n", "최초 커밋")
            fixture.commit(FILE_NAME, "second\n", "두번째 커밋")

            fixture.gateway.hardReset(first)

            fixture.headHash() shouldBe first.value
            fixture.read(FILE_NAME) shouldBe "first\n"
            fixture.status().isClean shouldBe true
        }
    }

    test("존재하지 않는 커밋으로 reset 하면 NotFound(COMMIT) 를 던진다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")

            val thrown = shouldThrow<UndineException.NotFound> {
                fixture.gateway.reset(CommitId.of(MISSING_COMMIT_HASH), ResetMode.MIXED)
            }

            thrown.kind shouldBe UndineException.NotFound.Kind.COMMIT
            thrown.name shouldBe MISSING_COMMIT_HASH
        }
    }

    test("커밋이 없는 저장소의 stash push 는 작업명을 담은 GitOperationFailed 로 번역된다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.write(FILE_NAME, "content\n")

            val thrown = shouldThrow<UndineException.GitOperationFailed> {
                fixture.gateway.stashPush(includeUntracked = false)
            }

            thrown.operation shouldBe "worktreeops.stashPush"
            thrown.cause shouldNotBe null
        }
    }

    test("저장소를 열기 전에는 StateViolation 으로 실패한다 — 빈 결과로 뭉뚱그리지 않는다") {
        RepositoryFixture(tempdir()).use { fixture ->
            shouldThrow<UndineException.StateViolation> { fixture.gateway.stashList() }
        }
    }

    test("파괴적 연산은 별도 메서드이며 boolean 플래그도 전체 clear API 도 없다") {
        // CommitId 는 value class 라 JVM 메서드명이 `reset-<해시>` 로 맹글링된다 — 접미사를 떼고 본다.
        val methods = WorktreeOpsGateway::class.java.methods.associateBy { it.name.substringBefore('-') }
        val names = methods.keys

        names shouldContain "hardReset"
        names shouldContain "stashDrop"
        names.none { it.contains("clear", ignoreCase = true) } shouldBe true

        val destructiveParameters = methods
            .filterKeys { it == "reset" || it == "hardReset" || it == "stashDrop" }
            .values
            .flatMap { it.parameterTypes.toList() }
        destructiveParameters.isEmpty() shouldBe false
        destructiveParameters.none {
            it == Boolean::class.javaPrimitiveType || it == java.lang.Boolean::class.java
        } shouldBe true
    }

    test("취소된 호출은 CancellationException 그대로 전파된다 — GitOperationFailed 로 번역하지 않는다") {
        openRepositoryFixture(tempdir()).use { fixture ->
            fixture.commit(FILE_NAME, "base\n", "최초 커밋")

            val started = CompletableDeferred<Unit>()
            val escaped = CompletableDeferred<Throwable>()
            coroutineScope {
                // while(true) 로 도는 이유 — isActive 검사로 빠져나가면 취소가 어느 예외로 나왔는지 못 본다.
                val caller = launch(Dispatchers.Default) {
                    try {
                        while (true) {
                            fixture.gateway.stashList()
                            started.complete(Unit)
                        }
                    } catch (thrown: Throwable) {
                        escaped.complete(thrown)
                    }
                }
                started.await()
                caller.cancel()
                caller.join()
            }

            escaped.await().shouldBeInstanceOf<CancellationException>()
        }
    }
})
