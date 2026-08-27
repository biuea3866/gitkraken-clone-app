package dev.undine.infrastructure.git.reflog

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.RefMoveConfirmation
import dev.undine.domain.reflog.UnreachableCommitScan
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

private const val MAIN = "main"
private const val SIDE = "side"
private const val CODE = "code.txt"
private const val KEEP_TAG = "keep"

private const val FIRST = "처음 만든다"
private const val SECOND = "둘째 커밋"
private const val ON_SIDE = "곁가지 커밋"
private const val DETACHED = "detached HEAD 커밋"

private val AUTHOR = PersonIdent(
    "작성자",
    "author@example.invalid",
    Instant.parse("2025-01-01T00:00:00Z"),
    ZoneOffset.UTC,
)

/**
 * reflog Gateway — **실제 임시 저장소**로 검증한다.
 *
 * reflog 는 "참조가 어디에 있었는가" 를 답하는 기능이라 Mock 으로는 아무것도 검증하지 못한다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1).
 */
class ReflogGatewayImplSpec : FunSpec({

    test("reset 이후 HEAD reflog 에 이전 위치가 기록돼 조회된다") {
        val work = tempdir().also(::seedTwoCommits)
        val discarded = commitOf(work, SECOND)
        resetHardToParent(work)

        val page = withReflogGateway(work) { gateway -> gateway.headReflog(limit = 10) }

        // 최신 항목이 앞이다 — reset 이 방금 버린 커밋이 그 항목의 '이전 위치' 로 남는다.
        page.entries.first().from shouldBe discarded
        // git 이 남긴 설명을 가공하지 않고 그대로 전한다 — 왜 움직였는지의 유일한 단서다.
        page.entries.first().action shouldContainText "HEAD~1"
        page.mayBeExpired shouldBe false
    }

    test("브랜치 삭제 후에도 reflog 로 삭제 직전 커밋을 찾을 수 있다") {
        val work = tempdir()
        // 브랜치가 사라지면 그 커밋은 어떤 ref 로도 조회되지 않는다 — 지우기 전에 붙잡아 둔다.
        val lost = seedDeletedSideBranch(work)

        val page = withReflogGateway(work) { gateway -> gateway.headReflog(limit = 20) }

        // 브랜치와 함께 그 ref 의 reflog 파일도 사라진다 — 되찾을 단서는 HEAD reflog 에 남는다.
        page.entries.map { entry -> entry.to } shouldContain lost
    }

    test("지정한 ref 의 reflog 를 시각·동작·이전 해시와 함께 조회한다") {
        val work = tempdir().also(::seedTwoCommits)
        val first = commitOf(work, FIRST)
        val second = commitOf(work, SECOND)

        val page = withReflogGateway(work) { gateway ->
            gateway.refReflog(RefName(Constants.R_HEADS + MAIN), limit = 10)
        }

        page.entries.first().to shouldBe second
        page.entries.first().from shouldBe first
        page.entries.first().who.email shouldBe AUTHOR.emailAddress
        page.entries.last().from shouldBe null // 저장소 최초 항목은 이전이 없다.
    }

    test("reflog 가 없는 ref 를 조회하면 부재로 실패한다") {
        val work = tempdir().also(::seedTwoCommits)

        // 빈 결과를 주면 화면이 "움직인 적 없는 브랜치" 로 오해한다.
        val failure = withReflogGateway(work) { gateway ->
            shouldThrow<UndineException.NotFound> {
                gateway.refReflog(RefName(Constants.R_HEADS + "없는브랜치"), limit = 10)
            }
        }
        failure.kind shouldBe UndineException.NotFound.Kind.REF
    }

    test("새로 만든 저장소의 reflog 조회는 예외 없이 빈 결과와 만료 가능성을 준다") {
        val work = tempdir().also(::seedEmptyRepository)

        val page = withReflogGateway(work) { gateway -> gateway.headReflog(limit = 10) }

        page.entries.shouldBeEmpty()
        // 커밋 0건과 90일 만료를 화면이 구분할 수 있게 값으로 알린다.
        page.mayBeExpired shouldBe true
    }

    test("복구 기본 동작은 새 브랜치 생성이고 기존 ref 를 건드리지 않는다") {
        val work = tempdir().also(::seedTwoCommits)
        val lost = commitOf(work, FIRST)
        val mainBefore = commitOf(work, SECOND)

        val created = withReflogGateway(work) { gateway ->
            gateway.recover(lost, RecoveryTarget.NewBranch(RefName("rescue")))
        }

        created.ref shouldBe RefName("rescue")
        resolve(work, "rescue") shouldBe lost
        resolve(work, MAIN) shouldBe mainBefore
        // 복구와 같은 임계 구역에서 캡처한 기준 상태를 결과가 함께 준다 (UND-73).
        created.baseline shouldBe RepositoryBaseline(branch = RefName(MAIN), head = mainBefore)
    }

    test("이미 있는 이름으로 복구하면 덮어쓰지 않고 거부한다") {
        val work = tempdir().also(::seedTwoCommits)
        val lost = commitOf(work, FIRST)

        withReflogGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> {
                gateway.recover(lost, RecoveryTarget.NewBranch(RefName(MAIN)))
            }
        }
        resolve(work, MAIN) shouldBe commitOf(work, SECOND)
    }

    test("기존 ref 이동은 명시적 대상과 확인 값을 줬을 때만 수행된다") {
        val work = tempdir().also(::seedDetachedSideBranch)
        val lost = commitOf(work, FIRST)
        val displaced = resolve(work, SIDE)

        val moved = withReflogGateway(work) { gateway ->
            gateway.recover(
                lost,
                RecoveryTarget.MoveExisting(RefName(SIDE), RefMoveConfirmation.ofDisplacedCommit(displaced)),
            )
        }

        moved.ref shouldBe RefName(SIDE)
        resolve(work, SIDE) shouldBe lost
        // 이동은 HEAD 를 옮기지 않으므로 기준 상태는 체크아웃된 브랜치 그대로다.
        moved.baseline shouldBe RepositoryBaseline(branch = RefName(MAIN), head = resolve(work, MAIN))
    }

    test("reflog 를 꺼 둔 저장소에서도 이동이 밀어낸 커밋을 기록으로 남긴다") {
        val work = tempdir().also(::seedSideBranchWithoutReflog)
        val lost = commitOf(work, FIRST)
        val displaced = resolve(work, SIDE)

        withReflogGateway(work) { gateway ->
            gateway.recover(
                lost,
                RecoveryTarget.MoveExisting(RefName(SIDE), RefMoveConfirmation.ofDisplacedCommit(displaced)),
            )
        }

        resolve(work, SIDE) shouldBe lost
        // 밀려난 커밋은 어떤 ref 로도 도달되지 않는다 — 기록이 없으면 다음 gc 때 진짜로 사라진다.
        reflogFromIds(work, Constants.R_HEADS + SIDE) shouldContain displaced
    }

    test("확인 뒤 ref 가 움직였으면 옮기지 않는다") {
        val work = tempdir().also(::seedDetachedSideBranch)
        val lost = commitOf(work, FIRST)
        val staleConfirmation = RefMoveConfirmation.ofDisplacedCommit(lost)
        val sideBefore = resolve(work, SIDE)

        withReflogGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> {
                gateway.recover(lost, RecoveryTarget.MoveExisting(RefName(SIDE), staleConfirmation))
            }
        }
        resolve(work, SIDE) shouldBe sideBefore
    }

    test("재검증과 갱신 사이에 참조가 움직였으면 비교-교환이 덮어쓰기를 막는다") {
        val work = tempdir().also(::seedDetachedSideBranch)
        val recoveryPoint = ObjectId.fromString(commitOf(work, FIRST).value)
        val sideBefore = resolve(work, SIDE)

        // 재검증 직후 외부 git 프로세스가 ref 를 옮긴 창을 재현한다 — 읽은 값(recoveryPoint)과
        // 지금 값(곁가지 커밋)이 어긋나므로 갱신 자체가 거부돼야 한다.
        Git.open(work).use { git ->
            shouldThrow<UndineException.StateViolation> {
                git.repository.moveRefTo(Constants.R_HEADS + SIDE, recoveryPoint, recoveryPoint)
            }
        }

        // 확인받지 않은 커밋을 밀어내지 않았다.
        resolve(work, SIDE) shouldBe sideBefore
    }

    test("없는 ref 를 옮기려 하면 상태 위반으로 거부한다") {
        val work = tempdir().also(::seedTwoCommits)
        val lost = commitOf(work, FIRST)
        val confirmation = RefMoveConfirmation.ofDisplacedCommit(lost)

        withReflogGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> {
                gateway.recover(lost, RecoveryTarget.MoveExisting(RefName("없는브랜치"), confirmation))
            }
        }
    }

    test("저장소에 없는 커밋으로 복구하려 하면 부재로 실패한다") {
        val work = tempdir().also(::seedTwoCommits)
        val absent = CommitId.of("0".repeat(Constants.OBJECT_ID_STRING_LENGTH))

        val failure = withReflogGateway(work) { gateway ->
            shouldThrow<UndineException.NotFound> {
                gateway.recover(absent, RecoveryTarget.NewBranch(RefName("rescue")))
            }
        }
        failure.kind shouldBe UndineException.NotFound.Kind.COMMIT
    }

    test("JGit 이 거부하는 이름은 Git 연산 실패로 번역된다") {
        val work = tempdir().also(::seedTwoCommits)
        val lost = commitOf(work, FIRST)

        // JGit 예외를 그대로 올리지 않는다 — 화면이 UndineException 으로만 분기할 수 있어야 한다.
        withReflogGateway(work) { gateway ->
            shouldThrow<UndineException.GitOperationFailed> {
                gateway.recover(lost, RecoveryTarget.NewBranch(RefName("잘못된..이름")))
            }
        }
    }

    test("도달 불가 커밋 탐색이 reflog 에도 없는 커밋을 찾아낸다") {
        val work = tempdir().also(::seedTwoCommits)
        val orphan = insertOrphanCommit(work)
        val discardedByReset = commitOf(work, SECOND)
        val stillReachable = commitOf(work, FIRST)
        resetHardToParent(work)

        val scan = withReflogGateway(work) { gateway -> gateway.unreachableCommits(limit = 10) }

        val found = scan.shouldBeInstanceOf<UnreachableCommitScan.Scanned>().commits.map { it.id }
        found shouldContain orphan
        // reset 이 버린 커밋은 reflog 로 되찾을 수 있으므로 이 진입점의 대상이 아니다.
        found shouldNotContain discardedByReset
        found shouldNotContain stillReachable
    }

    test("도달 불가 커밋이 없으면 미지원이 아니라 훑은 빈 결과다") {
        val work = tempdir().also(::seedTwoCommits)

        withReflogGateway(work) { gateway -> gateway.unreachableCommits(limit = 10) }
            .shouldBeInstanceOf<UnreachableCommitScan.Scanned>()
            .commits
            .shouldBeEmpty()
    }

    test("파일 기반이 아닌 객체 DB 는 빈 결과가 아니라 미지원으로 알린다") {
        // 객체를 나열할 공개 API 가 없는 저장 방식이다. 빈 목록으로 답하면 잃은 커밋을 찾으러 온
        // 사용자에게 "없다" 는 오답이 되므로 미지원임을 타입으로 알려야 한다.
        InMemoryRepository(DfsRepositoryDescription("메모리 저장소")).use { repository ->
            repository.scanUnreachableCommits(limit = 10)
                .shouldBeInstanceOf<UnreachableCommitScan.NotSupported>()
                .reason shouldBe UnreachableCommitScan.NotSupported.Reason.NON_FILE_OBJECT_DATABASE
        }
    }

    test("annotated tag 만 가리키는 커밋은 도달 불가 후보가 아니다") {
        val work = tempdir()
        val tagged = seedTaggedDeletedBranch(work)
        // 브랜치도 reflog 도 없는 상태에서 **태그를 벗겨야** 도달성이 성립한다는 것을 확인한다.
        check(File(work, ".git/logs/HEAD").delete())

        val found = withReflogGateway(work) { gateway -> gateway.unreachableCommits(limit = 10) }
            .shouldBeInstanceOf<UnreachableCommitScan.Scanned>()
            .commits
            .map { commit -> commit.id }

        found shouldNotContain tagged
    }

    test("detached HEAD 의 현재 커밋은 도달 불가 후보가 아니다") {
        val work = tempdir()
        val detached = initRepository(work).use { git ->
            val first = git.commitFile(FIRST, "첫 줄\n")
            git.checkout().setName(first.name).call()
            CommitId.of(git.commitFile(DETACHED, "detached 줄\n").name)
        }
        // 이 검증은 reflog 가 아닌 **현재 HEAD** 가 도달성 시작점임을 확인한다.
        check(File(work, ".git/logs/HEAD").delete())

        val found = withReflogGateway(work) { gateway -> gateway.unreachableCommits(limit = 10) }
            .shouldBeInstanceOf<UnreachableCommitScan.Scanned>()
            .commits
            .map { commit -> commit.id }

        found shouldNotContain detached
    }
})

/**
 * reflog 항목의 신원은 커밋 작성자가 아니라 **저장소 설정의 사용자**다. 실행 환경의 전역 git 설정에
 * 결과가 흔들리지 않도록 저장소마다 고정한다.
 */
private fun initRepository(work: File): Git =
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().apply {
        repository.config.run {
            setString("user", null, "name", AUTHOR.name)
            setString("user", null, "email", AUTHOR.emailAddress)
            save()
        }
    }

/** 커밋 2건이 쌓인 main 브랜치. */
private fun seedTwoCommits(work: File) {
    initRepository(work).use { git ->
        git.commitFile(FIRST, "첫 줄\n")
        git.commitFile(SECOND, "첫 줄\n둘째 줄\n")
    }
}

/** 곁가지에 커밋한 뒤 main 으로 돌아와 그 브랜치를 지운 이력. 지워진 커밋을 돌려준다. */
private fun seedDeletedSideBranch(work: File): CommitId =
    initRepository(work).use { git ->
        git.commitFile(FIRST, "첫 줄\n")
        git.checkout().setCreateBranch(true).setName(SIDE).call()
        val lost = CommitId.of(git.commitFile(ON_SIDE, "곁가지 줄\n").name)
        git.checkout().setName(MAIN).call()
        git.branchDelete().setBranchNames(SIDE).setForce(true).call()
        lost
    }

/**
 * 곁가지 커밋에 annotated tag 를 붙인 뒤 그 브랜치를 지운 이력. 태그만 남은 커밋을 돌려준다.
 *
 * 태그 객체는 커밋이 아니라 커밋을 **가리키는** 객체라, 벗기지 않으면 그 커밋이 도달 불가로 잘못
 * 보고된다 — 이 씨앗은 그 경로를 실제 저장소로 재현한다.
 */
private fun seedTaggedDeletedBranch(work: File): CommitId =
    initRepository(work).use { git ->
        git.commitFile(FIRST, "첫 줄\n")
        git.checkout().setCreateBranch(true).setName(SIDE).call()
        val tagged = git.commitFile(ON_SIDE, "곁가지 줄\n")
        git.tag()
            .setName(KEEP_TAG)
            .setAnnotated(true)
            .setMessage("보존")
            .setTagger(AUTHOR)
            .setObjectId(tagged)
            .call()
        git.checkout().setName(MAIN).call()
        git.branchDelete().setBranchNames(SIDE).setForce(true).call()
        CommitId.of(tagged.name)
    }

/** main 을 체크아웃한 채 남아 있는 곁가지 브랜치 — 체크아웃되지 않은 ref 를 옮기는 대상이다. */
private fun seedDetachedSideBranch(work: File) {
    initRepository(work).use { git ->
        git.commitFile(FIRST, "첫 줄\n")
        git.checkout().setCreateBranch(true).setName(SIDE).call()
        git.commitFile(ON_SIDE, "곁가지 줄\n")
        git.checkout().setName(MAIN).call()
    }
}

/**
 * `core.logAllRefUpdates=false` 이고 기록 파일도 없는 저장소에 곁가지 브랜치만 남긴다.
 *
 * 이 설정에서 JGit 은 ref 갱신 기록을 건너뛴다 — 복구 이동이 밀어낸 커밋의 흔적이 남는지를
 * 확인하려면 기록이 저절로 생기지 않는 환경이어야 한다.
 */
private fun seedSideBranchWithoutReflog(work: File) {
    initRepository(work).use { git ->
        git.repository.config.run {
            setBoolean("core", null, "logAllRefUpdates", false)
            save()
        }
        git.commitFile(FIRST, "첫 줄\n")
        git.checkout().setCreateBranch(true).setName(SIDE).call()
        git.commitFile(ON_SIDE, "곁가지 줄\n")
        git.checkout().setName(MAIN).call()
    }
    // 초기화가 미리 만들어 둔 기록까지 지워 "reflog 가 아예 없는" 상태로 맞춘다.
    check(File(work, ".git/logs").deleteRecursively())
}

/** [ref] reflog 각 항목의 '이전 위치'. 기록 자체가 없으면 빈 목록이다. */
private fun reflogFromIds(work: File, ref: String): List<CommitId> = Git.open(work).use { git ->
    git.reflog().setRef(ref).call()
        .map { entry -> entry.oldId }
        .filterNot { oldId -> oldId == ObjectId.zeroId() }
        .map { oldId -> CommitId.of(oldId.name) }
}

/** 커밋이 하나도 없는 갓 만든 저장소. */
private fun seedEmptyRepository(work: File) {
    initRepository(work).close()
}

private fun Git.commitFile(message: String, content: String): RevCommit {
    File(repository.workTree, CODE).writeText(content)
    add().addFilepattern(CODE).call()
    return commit().setMessage(message).setAuthor(AUTHOR).setCommitter(AUTHOR).call()
}

private fun resetHardToParent(work: File) {
    Git.open(work).use { git ->
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
    }
}

/**
 * 어떤 참조도 reflog 도 가리키지 않는 커밋을 객체 DB 에 직접 넣는다.
 *
 * 중단된 연산이 남긴 커밋과 같은 상태를 결정적으로 재현하는 방법이다 — reflog 로는 찾을 수 없다.
 */
private fun insertOrphanCommit(work: File): CommitId = Git.open(work).use { git ->
    val repository = git.repository
    val head = repository.resolve(Constants.HEAD)
    val tree = RevWalk(repository).use { walk -> walk.parseCommit(head).tree.id }
    repository.newObjectInserter().use { inserter ->
        val builder = CommitBuilder().apply {
            setTreeId(tree)
            setParentId(head)
            author = AUTHOR
            committer = AUTHOR
            message = "참조도 reflog 도 없는 커밋"
        }
        val inserted = inserter.insert(builder)
        inserter.flush()
        CommitId.of(inserted.name)
    }
}

private fun commitOf(work: File, message: String): CommitId = Git.open(work).use { git ->
    CommitId.of(git.log().all().call().first { it.fullMessage.trim() == message }.name)
}

private fun resolve(work: File, ref: String): CommitId = Git.open(work).use { git ->
    CommitId.of(git.repository.resolve(ref).name)
}

/**
 * 저장소를 연 [GitAccess] 로 Gateway 를 만들어 [block] 을 수행하고, 성공·실패와 무관하게 닫는다.
 *
 * 핸들을 열어 두고 반환하면 테스트가 끝나도 JGit 이 잡은 파일 핸들이 남아 정리 시점이
 * 비결정적이 된다 ([`jgit-usage`](../../../../../../../../.agent/rules/jgit-usage.md) 규칙 1).
 */
private suspend fun <T> withReflogGateway(work: File, block: suspend (ReflogGatewayImpl) -> T): T {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(work.absolutePath)) { }
    return try {
        block(ReflogGatewayImpl(gitAccess))
    } finally {
        gitAccess.close()
    }
}
