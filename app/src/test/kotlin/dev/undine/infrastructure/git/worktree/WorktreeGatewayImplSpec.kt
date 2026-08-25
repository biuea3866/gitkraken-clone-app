package dev.undine.infrastructure.git.worktree

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.worktree.WorktreeState
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Instant
import java.time.ZoneOffset

private const val MAIN = "main"
private const val FEATURE = "feature"
private const val OTHER = "other"
private const val CODE = "code.txt"
private const val NOTES = "notes.txt"
private const val IGNORED = "local.log"
private const val NESTED = "nested"
private const val FIRST = "처음 만든다"
private const val LOCAL_ONLY = "커밋하지 않은 로컬 파일"
private const val WORKTREES_PATH = ".git/worktrees"

private val AUTHOR = PersonIdent(
    "작성자",
    "author@example.invalid",
    Instant.parse("2025-01-01T00:00:00Z"),
    ZoneOffset.UTC,
)

/**
 * worktree Gateway — **실제 임시 저장소**로 검증한다.
 *
 * 이 구현은 `.git/worktrees` 저수준 형식을 직접 다루므로 Mock 으로는 아무것도 증명하지 못한다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1).
 */
class WorktreeGatewayImplSpec : FunSpec({

    test("목록에 메인 worktree 가 구분되어 표시된다") {
        val work = tempdir().also(::seedRepository)

        val listing = withWorktreeGateway(work) { gateway -> gateway.list() }

        listing.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
        listing.main?.branch shouldBe RefName("refs/heads/$MAIN")
        listing.main?.path shouldBe RepositoryPath(work.canonicalPath)
        listing.unsupported.shouldBeEmpty()
    }

    test("사용 중이 아닌 브랜치로 추가하면 지정 경로에 체크아웃되고 목록에 반영된다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val listing = withWorktreeGateway(work) { gateway ->
            val added = gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            added.state shouldBe WorktreeState.LINKED
            added.branch shouldBe RefName("refs/heads/$FEATURE")
            gateway.list()
        }

        // 커밋된 내용이 실제로 체크아웃돼야 worktree 로 쓸 수 있다.
        File(target, CODE).readText() shouldBe FIRST
        listing.worktrees.map { it.name } shouldContainExactlyInAnyOrder listOf(work.name, FEATURE)
        listing.worktrees.first { it.name == FEATURE }.state shouldBe WorktreeState.LINKED
    }

    test("생성된 메타데이터는 표준 세 파일을 갖추고 중간 임시 디렉터리를 남기지 않는다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
        }

        val registration = File(work, ".git/worktrees/$FEATURE")
        registration.list()?.toList()
            ?.shouldContainAll(listOf("gitdir", "commondir", "HEAD"))
        // rename 으로 제자리에 놓으므로 완성 전 상태가 남지 않는다.
        File(work, ".git/worktrees").list()?.toList() shouldContainExactly listOf(FEATURE)
        File(target, ".git").readText().trim() shouldBe "gitdir: ${registration.canonicalPath}"
        File(registration, "gitdir").readText().trim() shouldBe File(target, ".git").canonicalPath
        File(registration, "commondir").readText().trim() shouldBe "../.."
    }

    test("이미 다른 worktree 가 체크아웃한 브랜치로 추가하면 거부된다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val first = File(tempdir(), FEATURE)
        val second = File(tempdir(), OTHER)

        val failure = withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(first.absolutePath), RefName(FEATURE))
            shouldThrow<UndineException.StateViolation> {
                gateway.add(RepositoryPath(second.absolutePath), RefName(FEATURE))
            }
        }

        failure.detail shouldContain FEATURE
        second.exists() shouldBe false
    }

    test("메인 worktree 가 체크아웃한 브랜치로 추가해도 거부된다") {
        val work = tempdir().also(::seedRepository)
        val target = File(tempdir(), MAIN)

        val failure = withWorktreeGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> {
                gateway.add(RepositoryPath(target.absolutePath), RefName(MAIN))
            }
        }

        failure.detail shouldContain MAIN
    }

    test("없는 브랜치로 추가하면 브랜치를 만들지 않고 참조 부재로 보고한다") {
        val work = tempdir().also(::seedRepository)
        val target = File(tempdir(), FEATURE)

        val failure = withWorktreeGateway(work) { gateway ->
            shouldThrow<UndineException.NotFound> {
                gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            }
        }

        failure.kind shouldBe UndineException.NotFound.Kind.REF
        failure.name shouldBe FEATURE
        // 오타를 새 브랜치로 굳히지 않는다.
        branchNames(work) shouldContainExactly listOf(MAIN)
        target.exists() shouldBe false
    }

    test("비어 있지 않은 경로에는 추가하지 않는다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE).apply { mkdirs() }
        File(target, NOTES).writeText("남아 있던 파일")

        val failure = withWorktreeGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> {
                gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            }
        }

        failure.detail shouldContain target.name
        File(target, NOTES).exists() shouldBe true
    }

    test("더티한 worktree 제거는 강제 없이 항상 거부된다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val failure = withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            File(target, CODE).writeText("커밋하지 않은 수정")
            shouldThrow<UndineException.DirtyWorkingTree> { gateway.remove(FEATURE) }
        }

        failure.paths shouldContainExactly listOf(CODE)
        // 거부됐으므로 사용자의 미커밋 작업이 그대로 남아야 한다.
        File(target, CODE).readText() shouldBe "커밋하지 않은 수정"
    }

    test("추적되지 않은 파일만 있어도 더티로 보고 제거를 거부한다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val failure = withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            File(target, NOTES).writeText("아직 추가 안 한 메모")
            shouldThrow<UndineException.DirtyWorkingTree> { gateway.remove(FEATURE) }
        }

        failure.paths shouldContainExactly listOf(NOTES)
    }

    test("깨끗한 worktree 제거는 디렉터리와 등록을 함께 지운다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val listing = withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            gateway.remove(FEATURE)
            gateway.list()
        }

        target.exists() shouldBe false
        File(work, ".git/worktrees/$FEATURE").exists() shouldBe false
        listing.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
    }

    test("메인 worktree 는 제거할 수 없다") {
        val work = tempdir().also(::seedRepository)

        val failure = withWorktreeGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> { gateway.remove(work.name) }
        }

        failure.detail shouldContain work.name
        work.exists() shouldBe true
    }

    test("앱이 현재 열고 있는 worktree 는 제거할 수 없다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)
        withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
        }

        // 이번에는 연결 worktree 자체를 연 채로 자기 자신을 지우려 한다.
        val failure = withWorktreeGateway(target) { gateway ->
            shouldThrow<UndineException.StateViolation> { gateway.remove(FEATURE) }
        }

        failure.detail shouldContain FEATURE
        target.exists() shouldBe true
    }

    test("없는 worktree 를 제거하면 워크트리 부재로 보고한다") {
        val work = tempdir().also(::seedRepository)

        val failure = withWorktreeGateway(work) { gateway ->
            shouldThrow<UndineException.NotFound> { gateway.remove(FEATURE) }
        }

        failure.kind shouldBe UndineException.NotFound.Kind.WORKTREE
        failure.name shouldBe FEATURE
    }

    test("디렉터리가 사라진 worktree 는 고아로 구분되고 제거로 등록만 정리된다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val listing = withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            target.deleteRecursively()
            gateway.list()
        }

        listing.worktrees.first { it.name == FEATURE }.state shouldBe WorktreeState.ORPHANED

        val afterRemoval = withWorktreeGateway(work) { gateway ->
            gateway.remove(FEATURE)
            gateway.list()
        }

        afterRemoval.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
        File(work, ".git/worktrees/$FEATURE").exists() shouldBe false
    }

    test("읽을 수 없는 등록은 빈 목록으로 뭉개지 않고 미지원으로 보고된다") {
        val work = tempdir().also(::seedRepository)
        File(work, ".git/worktrees/$OTHER").mkdirs()
        File(work, ".git/worktrees/$OTHER/gitdir").writeText("/nowhere/$OTHER/.git\n")

        val listing = withWorktreeGateway(work) { gateway -> gateway.list() }

        // HEAD·commondir 이 없다 — 형식을 추측하지 않고 미지원으로 남긴다.
        listing.unsupported.map { it.name } shouldContainExactly listOf(OTHER)
        listing.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
    }

    test("읽을 수 없는 등록은 제거 대상이 되지 않는다 — 부재로 뭉개지 않는다") {
        val work = tempdir().also(::seedRepository)
        File(work, ".git/worktrees/$OTHER").mkdirs()
        File(work, ".git/worktrees/$OTHER/gitdir").writeText("/nowhere/$OTHER/.git\n")

        val failure = withWorktreeGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> { gateway.remove(OTHER) }
        }

        failure.detail shouldContain OTHER
        File(work, ".git/worktrees/$OTHER").exists() shouldBe true
    }

    test("읽을 수 없는 등록이 있으면 중복 체크아웃을 확인할 수 없어 추가를 거부한다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        File(work, ".git/worktrees/$OTHER").mkdirs()
        File(work, ".git/worktrees/$OTHER/gitdir").writeText("/nowhere/$OTHER/.git\n")
        val target = File(tempdir(), FEATURE)

        val failure = withWorktreeGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> {
                gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            }
        }

        failure.detail shouldContain OTHER
        target.exists() shouldBe false
    }

    test("ignore 된 파일만 있어도 더티로 보고 제거를 거부한다") {
        val work = tempdir().also(::seedRepository).also(::commitIgnoreRule).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val failure = withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            File(target, IGNORED).writeText(LOCAL_ONLY)
            shouldThrow<UndineException.DirtyWorkingTree> { gateway.remove(FEATURE) }
        }

        failure.paths shouldContainExactly listOf(IGNORED)
        // 제거는 디렉터리를 통째로 지운다 — 거부됐으니 사용자가 남겨 둔 파일이 그대로 있어야 한다.
        File(target, IGNORED).readText() shouldBe LOCAL_ONLY
    }

    test("등록의 HEAD 가 표준 형식이 아니면 detached 로 추측하지 않고 미지원으로 보고한다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val listing = withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            File(work, "$WORKTREES_PATH/$FEATURE/HEAD").writeText("알 수 없는 내용\n")
            gateway.list()
        }

        listing.unsupported.map { it.name } shouldContainExactly listOf(FEATURE)
        listing.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
    }

    test("gitdir 가 .git 파일을 가리키지 않으면 고아로 추측하지 않고 미지원으로 보고한다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val listing = withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            target.deleteRecursively()
            File(work, "$WORKTREES_PATH/$FEATURE/gitdir").writeText("/nowhere/$FEATURE/notgit\n")
            gateway.list()
        }

        // 부모를 worktree 디렉터리로 삼는 해석이 성립하지 않는다 — 고아로 뭉개지 않는다.
        listing.unsupported.map { it.name } shouldContainExactly listOf(FEATURE)
        listing.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
    }

    test("메타데이터를 쓰지 못하면 등록을 남기지 않고 실패로 보고한다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE).apply { mkdirs() }
        target.setWritable(false)

        try {
            withWorktreeGateway(work) { gateway ->
                shouldThrow<UndineException.GitOperationFailed> {
                    gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
                }
            }
            // 확정 지점(rename)에 닿지 못했으므로 등록도 완성 전 임시 디렉터리도 남지 않는다.
            File(work, WORKTREES_PATH).list()?.toList().orEmpty().shouldBeEmpty()
        } finally {
            target.setWritable(true)
        }
        target.list()?.toList().orEmpty().shouldBeEmpty()
    }

    test("디렉터리를 지우다 실패하면 등록을 남겨 제거를 다시 시도할 수 있다") {
        val work = tempdir().also(::seedRepository).also(::commitNestedFile).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)
        withWorktreeGateway(work) { gateway ->
            gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
        }
        val nested = File(target, NESTED)
        nested.setWritable(false)

        try {
            val listing = withWorktreeGateway(work) { gateway ->
                shouldThrow<UndineException.GitOperationFailed> { gateway.remove(FEATURE) }
                gateway.list()
            }
            // `.git` 과 등록이 그대로라 목록에 정상으로 잡힌다 — 그래서 다시 지울 수 있다.
            listing.worktrees.first { it.name == FEATURE }.state shouldBe WorktreeState.LINKED
            File(target, GIT_FILE_NAME).exists() shouldBe true
        } finally {
            nested.setWritable(true)
        }

        withWorktreeGateway(work) { gateway -> gateway.remove(FEATURE) }

        target.exists() shouldBe false
        File(work, "$WORKTREES_PATH/$FEATURE").exists() shouldBe false
    }

    test("비었는지 확인한 뒤 생긴 사용자 파일은 덮어쓰지도 지우지도 않고 생성을 실패시킨다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), "$NESTED/$FEATURE")

        val listing = withGitAccess(work) { gitAccess ->
            // 비었는지 확인한 뒤에 생긴 파일을 흉내 낸다 — 강제 체크아웃이거나 재귀 정리였다면
            // 여기서 말없이 사라진다.
            val racing = WorktreeGatewayImpl(gitAccess, ATOMIC_FILE_MOVE) { File(target, CODE).writeText(LOCAL_ONLY) }
            val failure = shouldThrow<UndineException.GitOperationFailed> {
                racing.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            }
            val cause = failure.cause
            cause.shouldBeInstanceOf<CheckoutConflictException>()
            // 남긴 디렉터리를 성공으로 뭉개지 않는다 — 지우지 못한 사실이 실패로 보고된다.
            cause.suppressed.toList().shouldNotBeEmpty()
            racing.list()
        }

        File(target, CODE).readText() shouldBe LOCAL_ONLY
        File(target, GIT_FILE_NAME).exists() shouldBe false
        File(work, "$WORKTREES_PATH/$FEATURE").exists() shouldBe false
        // 실패한 생성이 목록에 남지 않는다.
        listing.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
    }

    test("더티 검사 이후 파일이 생기면 아무것도 지우지 않고 제거를 중단한다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE)

        val listing = withGitAccess(work) { gitAccess ->
            WorktreeGatewayImpl(gitAccess).add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            // 더티 검사와 삭제 사이에 생긴 파일 — 기록에 없으므로 제거 대상이 아니다.
            val racing = WorktreeGatewayImpl(gitAccess, ATOMIC_FILE_MOVE) { File(target, NOTES).writeText(LOCAL_ONLY) }
            val failure = shouldThrow<UndineException.GitOperationFailed> { racing.remove(FEATURE) }
            failure.cause.shouldBeInstanceOf<IOException>()
            racing.list()
        }

        File(target, NOTES).readText() shouldBe LOCAL_ONLY
        File(target, CODE).readText() shouldBe FIRST
        File(target, GIT_FILE_NAME).exists() shouldBe true
        // `.git` 과 등록이 그대로라 목록에 정상으로 잡힌다 — 사용자가 정리한 뒤 다시 부를 수 있다.
        listing.worktrees.first { it.name == FEATURE }.state shouldBe WorktreeState.LINKED
    }

    test("연결 .git 배치가 원자적 rename 을 지원하지 않으면 등록도 임시 파일도 남기지 않는다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE).apply { mkdirs() }

        val listing = withGitAccess(work) { gitAccess ->
            val gateway = WorktreeGatewayImpl(gitAccess, refusingAtomicMoveTo(GIT_FILE_NAME), {})
            val failure = shouldThrow<UndineException.GitOperationFailed> {
                gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            }
            // 미지원 파일 시스템을 추측으로 넘기지 않고 IOException 으로 번역하되 원인은 보존한다.
            failure.cause.shouldBeInstanceOf<IOException>()
            failure.causeChain().filterIsInstance<AtomicMoveNotSupportedException>().shouldNotBeEmpty()
            gateway.list()
        }

        File(work, "$WORKTREES_PATH/$FEATURE").exists() shouldBe false
        File(work, WORKTREES_PATH).list()?.toList().orEmpty().shouldBeEmpty()
        target.list()?.toList().orEmpty().shouldBeEmpty()
        listing.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
    }

    test("등록 확정이 원자적 rename 을 지원하지 않으면 이미 놓은 .git 까지 되돌린다") {
        val work = tempdir().also(::seedRepository).also { createBranch(it, FEATURE) }
        val target = File(tempdir(), FEATURE).apply { mkdirs() }

        val listing = withGitAccess(work) { gitAccess ->
            val gateway = WorktreeGatewayImpl(gitAccess, refusingAtomicMoveTo(FEATURE), {})
            val failure = shouldThrow<UndineException.GitOperationFailed> {
                gateway.add(RepositoryPath(target.absolutePath), RefName(FEATURE))
            }
            failure.cause.shouldBeInstanceOf<IOException>()
            failure.causeChain().filterIsInstance<AtomicMoveNotSupportedException>().shouldNotBeEmpty()
            gateway.list()
        }

        File(work, "$WORKTREES_PATH/$FEATURE").exists() shouldBe false
        // 확정에 닿지 못했으므로 먼저 놓았던 `.git` 도 남기지 않는다 — 가리킬 등록이 없다.
        File(target, GIT_FILE_NAME).exists() shouldBe false
        File(work, WORKTREES_PATH).list()?.toList().orEmpty().shouldBeEmpty()
        listing.worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
    }

    test("메인 worktree 의 HEAD 가 표준 형식이 아니면 MAIN 대신 미지원으로 보고한다") {
        val work = tempdir().also(::seedRepository)

        val listing = withWorktreeGateway(work) { gateway ->
            File(work, "$GIT_FILE_NAME/HEAD").writeText("알 수 없는 내용\n")
            gateway.list()
        }

        // 메인도 예외가 아니다 — detached 로 추측하면 브랜치 없는 정상 worktree 로 보인다.
        listing.main shouldBe null
        listing.worktrees.shouldBeEmpty()
        listing.unsupported.map { it.name } shouldContainExactly listOf(work.name)
    }

    test("저장소가 열려 있지 않으면 빈 목록이 아니라 실패로 보고한다") {
        val gateway = WorktreeGatewayImpl(GitAccess())

        shouldThrow<UndineException.StateViolation> { gateway.list() }
    }
})

/** 커밋 하나를 가진 저장소를 만든다. 신원은 전역 git 설정에 흔들리지 않게 저장소마다 고정한다. */
private fun seedRepository(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        git.repository.config.apply {
            setString("user", null, "name", AUTHOR.name)
            setString("user", null, "email", AUTHOR.emailAddress)
            save()
        }
        File(work, CODE).writeText(FIRST)
        git.add().addFilepattern(CODE).call()
        git.commit().setMessage(FIRST).setAuthor(AUTHOR).setCommitter(AUTHOR).call()
    }
}

/** 추적되는 ignore 규칙을 커밋한다. 규칙 파일 자체가 미추적으로 잡히면 검증하려는 것이 가려진다. */
private fun commitIgnoreRule(work: File) {
    Git.open(work).use { git ->
        File(work, ".gitignore").writeText("$IGNORED\n")
        git.add().addFilepattern(".gitignore").call()
        git.commit().setMessage("ignore 규칙").setAuthor(AUTHOR).setCommitter(AUTHOR).call()
    }
}

/** 하위 디렉터리에 파일을 하나 커밋한다. 디렉터리 권한으로 삭제 실패를 주입하려면 중첩이 필요하다. */
private fun commitNestedFile(work: File) {
    Git.open(work).use { git ->
        File(work, NESTED).mkdirs()
        File(work, "$NESTED/$NOTES").writeText(FIRST)
        git.add().addFilepattern("$NESTED/$NOTES").call()
        git.commit().setMessage(NESTED).setAuthor(AUTHOR).setCommitter(AUTHOR).call()
    }
}

/**
 * 원인 사슬 전체. 코루틴 stacktrace 복원이 예외를 한 겹 복사해 끼우므로, 원인은 고정된 깊이가
 * 아니라 사슬 안에 있는지로 확인한다.
 */
private fun Throwable.causeChain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

/** 이름이 [destinationName] 인 곳으로 가는 rename 만 원자적 이동 미지원처럼 거절한다. */
private fun refusingAtomicMoveTo(destinationName: String): FileMove = { source, target ->
    if (target.fileName.toString() == destinationName) {
        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "테스트가 주입한 미지원 파일 시스템")
    }
    Files.move(source, target, ATOMIC_MOVE)
}

private fun createBranch(work: File, name: String) {
    Git.open(work).use { git -> git.branchCreate().setName(name).call() }
}

private fun branchNames(work: File): List<String> =
    Git.open(work).use { git -> git.branchList().call().map { it.name.removePrefix("refs/heads/") } }

/**
 * [work] 저장소를 연 [GitAccess] 로 Gateway 를 만들어 [block] 을 수행하고 반드시 닫는다.
 * 핸들 수명은 `GitAccess` 소유라 Gateway 가 닫지 않는다.
 */
private suspend fun <T> withWorktreeGateway(work: File, block: suspend (WorktreeGatewayImpl) -> T): T =
    withGitAccess(work) { gitAccess -> block(WorktreeGatewayImpl(gitAccess)) }

/**
 * [work] 저장소를 연 [GitAccess] 를 넘기고 반드시 닫는다. seam(파일 이동 · 검사 창)을 주입한
 * Gateway 를 **같은 저장소 핸들**로 만들어야 하는 테스트가 이 자리를 쓴다.
 */
private suspend fun <T> withGitAccess(work: File, block: suspend (GitAccess) -> T): T {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(work.absolutePath)) { }
    return try {
        block(gitAccess)
    } finally {
        gitAccess.close()
    }
}
