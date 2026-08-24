package dev.undine.infrastructure.git.merge

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeGateway
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.merge.SkipConfirmation
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryHolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch

private const val INITIAL_BRANCH = "main"
private const val FEATURE_BRANCH = "feature"
private const val SHARED_FILE = "shared.txt"
private const val MAIN_ONLY_FILE = "main-only.txt"
private const val FEATURE_ONLY_FILE = "feature-only.txt"
private const val OTHER_BRANCH = "other"
private const val OTHER_ONLY_FILE = "other-only.txt"
private const val MAIN_SECOND_FILE = "main-second.txt"
private const val ORPHAN_BRANCH = "orphan"
private const val ORPHAN_FILE = "orphan-only.txt"
private const val MISSING_BRANCH = "refs/heads/does-not-exist"
private const val BROKEN_BRANCH = "refs/heads/broken"

/** 저장소에 없는 객체 해시. 이 해시를 가리키는 참조는 존재하지만 읽는 순간 JGit 이 실패한다. */
private const val MISSING_OBJECT_SHA = "0123456789012345678901234567890123456789"
private const val IMPLEMENTATION_SOURCE_PATH = "src/main/kotlin/dev/undine/infrastructure/git/merge"

/** 잠금 점유 중에도 Gateway 호출이 끝나지 않는지 관측할 만큼만 기다린다. */
private const val LOCK_PROBE_MILLIS = 150L

/** 커밋 시각을 고정한다 — 테스트는 현재 시각에 의존하지 않는다. */
private val FIXED_IDENT = PersonIdent(
    "Undine Test",
    "test@undine.dev",
    Instant.ofEpochSecond(1_700_000_000L),
    ZoneOffset.UTC,
)

/**
 * 임시 디렉토리의 **실제** JGit 저장소. JGit 을 Mock 으로 대체하지 않고, 네트워크도 타지 않는다.
 *
 * 준비용 [Git] 과 [gateway] 가 같은 [Repository] 핸들을 공유하도록 [RepositoryHolder] 에 그 핸들을
 * 그대로 돌려준다.
 */
private class MergeFixture(val workDirectory: File) : AutoCloseable {

    private val git: Git = Git.init()
        .setDirectory(workDirectory)
        .setInitialBranch(INITIAL_BRANCH)
        .call()
    val repository: Repository = git.repository
    val gitAccess = GitAccess(RepositoryHolder { repository })
    val gateway: MergeGateway = MergeGatewayImpl(gitAccess)

    init {
        repository.config.apply {
            setString("user", null, "name", FIXED_IDENT.name)
            setString("user", null, "email", FIXED_IDENT.emailAddress)
            save()
        }
    }

    suspend fun open() {
        gitAccess.open(RepositoryPath(workDirectory.path)) { }
    }

    fun write(name: String, content: String) = File(workDirectory, name).writeText(content)

    fun read(name: String): String = File(workDirectory, name).readText()

    fun exists(name: String): Boolean = File(workDirectory, name).exists()

    fun stage(name: String) {
        git.add().addFilepattern(name).call()
    }

    fun commit(name: String, content: String, message: String): ObjectId {
        write(name, content)
        stage(name)
        return git.commit()
            .setMessage(message)
            .setAuthor(FIXED_IDENT)
            .setCommitter(FIXED_IDENT)
            .call()
    }

    fun branch(name: String) {
        git.branchCreate().setName(name).call()
    }

    fun checkout(name: String) {
        git.checkout().setName(name).call()
    }

    /** 공통 조상이 없는 브랜치를 만든다 — `git checkout --orphan` 과 같다. */
    fun orphan(name: String) {
        git.checkout().setOrphan(true).setName(name).call()
        git.rm().addFilepattern(SHARED_FILE).call()
    }

    fun head(): ObjectId = repository.resolve(Constants.HEAD)

    /**
     * 저장소에 없는 객체를 가리키는 참조를 만든다 — 이름은 찾히므로 `NotFound` 가 아니라
     * JGit 이 객체를 읽는 순간의 **예상하지 못한** 실패를 유도한다.
     */
    fun writeBrokenRef() {
        File(repository.directory, BROKEN_BRANCH).apply {
            parentFile.mkdirs()
            writeText("$MISSING_OBJECT_SHA\n")
        }
    }

    fun origHead(): ObjectId? = repository.readOrigHead()

    fun parentCountOfHead(): Int = RevWalk(repository).use { walk -> walk.parseCommit(head()).parentCount }

    fun firstParentOfHead(): ObjectId =
        RevWalk(repository).use { walk -> walk.parseCommit(head()).getParent(0).id }

    fun messages(): List<String> = git.log().call().map { it.shortMessage }

    fun conflictingPaths(): Set<String> = git.status().call().conflicting

    /**
     * 지금 사라질 편집을 모두 담은 확인 — 화면이 사용자에게 목록을 보여 주고 받아 오는 것과 같은 값이다.
     * 추적되지 않는 파일은 `reset --hard` 가 건드리지 않으므로 제외한다.
     */
    fun abortConfirmation(): AbortConfirmation {
        val status = git.status().call()
        val discarded = status.added + status.changed + status.removed +
            status.modified + status.missing + status.conflicting
        return AbortConfirmation.ofDiscardedPaths(discarded.distinct().sorted())
    }

    /** 지금 멈춰 있는 커밋에 대한 확인. 대상을 읽을 수 없으면 아무 커밋도 맞지 않는 확인을 만든다. */
    suspend fun skipConfirmation(): SkipConfirmation =
        SkipConfirmation.ofSkippedCommit(
            gateway.rebasingCommit() ?: CommitId.of("0".repeat(40)),
        )

    /**
     * `main` 과 `feature` 가 같은 파일을 다르게 바꾼 저장소. 반환 시점의 체크아웃 브랜치는 `main` 이다.
     * [featureOnlyChange] 가 true 면 feature 에 충돌하지 않는 커밋을 하나 더 얹는다.
     */
    fun givenConflictingBranches(featureOnlyChange: Boolean = false) {
        commit(SHARED_FILE, "base\n", "base")
        branch(FEATURE_BRANCH)
        checkout(FEATURE_BRANCH)
        commit(SHARED_FILE, "feature\n", "feature change")
        if (featureOnlyChange) commit(FEATURE_ONLY_FILE, "feature only\n", "feature only")
        checkout(INITIAL_BRANCH)
        commit(SHARED_FILE, "main\n", "main change")
    }

    /** 서로 다른 파일을 바꿔 충돌하지 않는 두 브랜치. 반환 시점의 체크아웃 브랜치는 `main` 이다. */
    fun givenDivergedBranches() {
        commit(SHARED_FILE, "base\n", "base")
        branch(FEATURE_BRANCH)
        checkout(FEATURE_BRANCH)
        commit(FEATURE_ONLY_FILE, "feature only\n", "feature only")
        checkout(INITIAL_BRANCH)
        commit(MAIN_ONLY_FILE, "main only\n", "main only")
    }

    /** `feature` 가 `main` 보다 앞서 있어 빨리 감기가 가능한 저장소. 체크아웃 브랜치는 `main` 이다. */
    fun givenFastForwardableFeature(): ObjectId {
        commit(SHARED_FILE, "base\n", "base")
        branch(FEATURE_BRANCH)
        checkout(FEATURE_BRANCH)
        val tip = commit(FEATURE_ONLY_FILE, "feature only\n", "feature only")
        checkout(INITIAL_BRANCH)
        return tip
    }

    override fun close() = git.close()
}

private suspend fun openFixture(directory: File): MergeFixture =
    MergeFixture(directory).also { it.open() }

/** 저장소를 새로 연 **다른 Gateway 인스턴스** — 앱을 다시 켠 상황을 흉내 낸다. */
private suspend fun <T> withReopenedGateway(directory: File, block: suspend (MergeGateway) -> T): T {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(directory.path)) { }
    return try {
        block(MergeGatewayImpl(gitAccess))
    } finally {
        gitAccess.close()
    }
}

class MergeGatewayImplSpec : FunSpec({

    test("충돌 없는 병합은 두 부모를 가진 병합 커밋을 만든다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenDivergedBranches()

            val result = fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            val succeeded = result.shouldBeInstanceOf<MergeResult.Succeeded>()
            succeeded.fastForward shouldBe false
            succeeded.head.value shouldBe fixture.head().name
            fixture.parentCountOfHead() shouldBe 2
            fixture.exists(FEATURE_ONLY_FILE) shouldBe true
            fixture.exists(MAIN_ONLY_FILE) shouldBe true
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("빨리 감기가 가능하면 옵션이 허용일 때 병합 커밋 없이 HEAD 만 옮긴다") {
        openFixture(tempdir()).use { fixture ->
            val featureTip = fixture.givenFastForwardableFeature()

            val result = fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            val succeeded = result.shouldBeInstanceOf<MergeResult.Succeeded>()
            succeeded.fastForward shouldBe true
            succeeded.head.value shouldBe featureTip.name
            fixture.parentCountOfHead() shouldBe 1
        }
    }

    test("빨리 감기가 가능해도 옵션이 비허용이면 병합 커밋을 만든다") {
        openFixture(tempdir()).use { fixture ->
            val featureTip = fixture.givenFastForwardableFeature()

            val result = fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = false)

            val succeeded = result.shouldBeInstanceOf<MergeResult.Succeeded>()
            succeeded.fastForward shouldBe false
            succeeded.head.value shouldNotBe featureTip.name
            fixture.parentCountOfHead() shouldBe 2
        }
    }

    test("병합 충돌은 예외가 아니라 Conflicted(paths) 이고 저장소는 MERGING 으로 남는다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()

            val result = fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            val conflicted = result.shouldBeInstanceOf<MergeResult.Conflicted>()
            conflicted.paths shouldContainExactly listOf(SHARED_FILE)
            fixture.gateway.repositoryState() shouldBe RepositoryState.MERGING
        }
    }

    test("이미 병합된 브랜치를 다시 병합하면 변경 없음으로 보고하고 HEAD 가 그대로다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenDivergedBranches()
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            val mergedHead = fixture.head()

            val result = fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            result shouldBe MergeResult.AlreadyUpToDate
            fixture.head() shouldBe mergedHead
        }
    }

    test("존재하지 않는 대상은 NotFound(REF) 로 번역된다") {
        openFixture(tempdir()).use { fixture ->
            fixture.commit(SHARED_FILE, "base\n", "base")

            val mergeFailure = shouldThrow<UndineException.NotFound> {
                fixture.gateway.merge(RefName(MISSING_BRANCH), allowFastForward = true)
            }
            val rebaseFailure = shouldThrow<UndineException.NotFound> {
                fixture.gateway.rebase(RefName(MISSING_BRANCH))
            }

            mergeFailure.kind shouldBe UndineException.NotFound.Kind.REF
            mergeFailure.name shouldBe MISSING_BRANCH
            rebaseFailure.kind shouldBe UndineException.NotFound.Kind.REF
        }
    }

    test("병합 충돌 뒤 abort 는 ORIG_HEAD 로 시작 전 HEAD 와 워킹트리를 복구한다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            val headBeforeMerge = fixture.head()
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            fixture.origHead() shouldBe headBeforeMerge

            fixture.gateway.abortMerge(fixture.abortConfirmation())

            fixture.head() shouldBe headBeforeMerge
            fixture.read(SHARED_FILE) shouldBe "main\n"
            fixture.conflictingPaths().isEmpty() shouldBe true
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("충돌을 해결하지 않은 채 continueMerge 하면 Conflicted 가 유지된다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            val result = fixture.gateway.continueMerge()

            result.shouldBeInstanceOf<MergeResult.Conflicted>().paths shouldContainExactly listOf(SHARED_FILE)
            fixture.gateway.repositoryState() shouldBe RepositoryState.MERGING
        }
    }

    test("충돌을 해결하고 stage 한 뒤 continueMerge 하면 병합 커밋이 만들어진다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            fixture.write(SHARED_FILE, "resolved\n")
            fixture.stage(SHARED_FILE)

            val result = fixture.gateway.continueMerge()

            val succeeded = result.shouldBeInstanceOf<MergeResult.Succeeded>()
            succeeded.head.value shouldBe fixture.head().name
            succeeded.fastForward shouldBe false
            fixture.parentCountOfHead() shouldBe 2
            fixture.read(SHARED_FILE) shouldBe "resolved\n"
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("rebase 는 현재 브랜치를 대상 위로 재배치한다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenDivergedBranches()
            val mainTip = fixture.head()
            fixture.checkout(FEATURE_BRANCH)

            val result = fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            val succeeded = result.shouldBeInstanceOf<RebaseResult.Succeeded>()
            succeeded.head.value shouldBe fixture.head().name
            fixture.firstParentOfHead() shouldBe mainTip
            fixture.messages() shouldContain "feature only"
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("이미 대상 위에 있는 브랜치를 rebase 하면 변경 없음으로 보고한다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenFastForwardableFeature()

            val result = fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            result shouldBe RebaseResult.AlreadyUpToDate
        }
    }

    test("rebase 충돌은 Conflicted 이고 저장소는 REBASING 으로 남는다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(FEATURE_BRANCH)

            val result = fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            result.shouldBeInstanceOf<RebaseResult.Conflicted>().paths shouldContainExactly listOf(SHARED_FILE)
            fixture.gateway.repositoryState() shouldBe RepositoryState.REBASING
        }
    }

    test("rebase 충돌 뒤 abort 는 ORIG_HEAD 로 시작 전 HEAD 와 워킹트리를 복구한다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(FEATURE_BRANCH)
            val headBeforeRebase = fixture.head()
            fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            fixture.origHead() shouldBe headBeforeRebase

            fixture.gateway.abortRebase(fixture.abortConfirmation())

            fixture.head() shouldBe fixture.origHead()
            fixture.head() shouldBe headBeforeRebase
            fixture.read(SHARED_FILE) shouldBe "feature\n"
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("되돌릴 시작 지점(ORIG_HEAD)이 없으면 중단은 StateViolation 으로 거부한다") {
        openFixture(tempdir()).use { fixture ->
            fixture.commit(SHARED_FILE, "base\n", "base")

            shouldThrow<UndineException.StateViolation> { fixture.gateway.abortMerge(fixture.abortConfirmation()) }
            shouldThrow<UndineException.StateViolation> { fixture.gateway.abortRebase(fixture.abortConfirmation()) }
        }
    }

    test("충돌을 해결하고 stage 한 뒤 continueRebase 하면 남은 커밋이 이어서 적용된다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches(featureOnlyChange = true)
            fixture.checkout(FEATURE_BRANCH)
            fixture.gateway.rebase(RefName(INITIAL_BRANCH))
            fixture.write(SHARED_FILE, "resolved\n")
            fixture.stage(SHARED_FILE)

            val result = fixture.gateway.continueRebase()

            result.shouldBeInstanceOf<RebaseResult.Succeeded>()
            fixture.messages() shouldContain "feature change"
            fixture.messages() shouldContain "feature only"
            fixture.messages() shouldContain "main change"
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("rebase 가 멈춘 커밋을 rebasingCommit 이 읽고, 진행 중이 아니면 null 이다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches(featureOnlyChange = true)
            fixture.checkout(FEATURE_BRANCH)

            // 리베이스 전에는 멈춘 커밋이 없다 — 대조할 대상 자체가 없는 상태다.
            fixture.gateway.rebasingCommit() shouldBe null

            fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            // 충돌로 멈춘 지금은 사라질 커밋을 화면이 읽어 확인에 담을 수 있어야 한다.
            val stopped = fixture.gateway.rebasingCommit()
            stopped shouldNotBe null

            fixture.gateway.skipRebaseCommit(fixture.skipConfirmation())

            // 끝난 뒤에는 다시 대상이 없다 — 낡은 확인으로 한 번 더 건너뛰지 못하게 하는 근거다.
            fixture.gateway.rebasingCommit() shouldBe null
        }
    }

    test("Gateway 는 서비스를 거치지 않아도 낡은 skip 확인을 거부한다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches(featureOnlyChange = true)
            fixture.checkout(FEATURE_BRANCH)
            fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            // 화면이 본 것과 다른 커밋을 확인한 상황. 서비스를 우회해 Gateway 를 직접 불러도 막혀야 한다.
            val stale = SkipConfirmation.ofSkippedCommit(CommitId.of("1".repeat(40)))
            shouldThrow<UndineException.StateViolation> { fixture.gateway.skipRebaseCommit(stale) }

            // 건너뛰지 않았으므로 리베이스는 여전히 그 커밋에서 멈춰 있다.
            fixture.gateway.repositoryState() shouldBe RepositoryState.REBASING
            fixture.messages() shouldNotContain "feature only"
        }
    }

    test("Gateway 는 확인 뒤에 생긴 편집이 있으면 중단하지 않는다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(FEATURE_BRANCH)
            fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            // 사용자가 확인한 시점의 목록을 잡아 둔다.
            val confirmed = fixture.abortConfirmation()
            // 확인과 실행 사이에 다른 편집이 인덱스에 올라온다 — 확인 대상이 아니었던 파일이다.
            fixture.write("late.txt", "written after the dialog\n")
            fixture.stage("late.txt")

            shouldThrow<UndineException.StateViolation> { fixture.gateway.abortRebase(confirmed) }

            // 중단하지 않았으므로 그 편집이 그대로 남는다.
            fixture.read("late.txt") shouldBe "written after the dialog\n"
            fixture.gateway.repositoryState() shouldBe RepositoryState.REBASING
        }
    }

    test("rebase 중 skip 하면 그 커밋이 결과 이력에서 빠진다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches(featureOnlyChange = true)
            fixture.checkout(FEATURE_BRANCH)
            fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            val result = fixture.gateway.skipRebaseCommit(fixture.skipConfirmation())

            result.shouldBeInstanceOf<RebaseResult.Succeeded>()
            fixture.messages() shouldNotContain "feature change"
            fixture.messages() shouldContain "feature only"
            fixture.read(SHARED_FILE) shouldBe "main\n"
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("진행 중인 병합 상태는 저장소를 다시 연 Gateway 에서도 읽힌다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            withReopenedGateway(fixture.workDirectory) { reopened ->
                reopened.repositoryState() shouldBe RepositoryState.MERGING
            }
        }
    }

    test("진행 중인 리베이스 상태는 저장소를 다시 연 Gateway 에서도 읽힌다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(FEATURE_BRANCH)
            fixture.gateway.rebase(RefName(INITIAL_BRANCH))

            withReopenedGateway(fixture.workDirectory) { reopened ->
                reopened.repositoryState() shouldBe RepositoryState.REBASING
            }
        }
    }

    test("예상하지 못한 JGit 실패는 작업명과 원인을 보존한 GitOperationFailed 로 번역된다") {
        openFixture(tempdir()).use { fixture ->
            fixture.commit(SHARED_FILE, "base\n", "base")
            fixture.writeBrokenRef()

            val thrown = shouldThrow<UndineException.GitOperationFailed> {
                fixture.gateway.merge(RefName(BROKEN_BRANCH), allowFastForward = true)
            }

            thrown.operation shouldBe "merge.merge"
            thrown.cause shouldNotBe null
        }
    }

    test("저장소를 열기 전에는 StateViolation 으로 실패한다 — 빈 결과로 뭉뚱그리지 않는다") {
        MergeFixture(tempdir()).use { fixture ->
            shouldThrow<UndineException.StateViolation> { fixture.gateway.repositoryState() }
        }
    }

    test("커밋이 없는 저장소는 EMPTY 로 읽히고 대상이 없어 시작하지 못한다") {
        openFixture(tempdir()).use { fixture ->
            fixture.gateway.repositoryState() shouldBe RepositoryState.EMPTY

            shouldThrow<UndineException.NotFound> {
                fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            }
            shouldThrow<UndineException.NotFound> { fixture.gateway.rebase(RefName(FEATURE_BRANCH)) }

            // 시작 지점이 없으므로 ORIG_HEAD 도 남지 않는다 — 되돌릴 곳이 없다.
            fixture.origHead() shouldBe null
            fixture.gateway.repositoryState() shouldBe RepositoryState.EMPTY
        }
    }

    test("커밋이 하나뿐인 저장소에서 같은 커밋을 가리키는 브랜치는 변경 없음이다") {
        openFixture(tempdir()).use { fixture ->
            val root = fixture.commit(SHARED_FILE, "base\n", "base")
            fixture.branch(FEATURE_BRANCH)

            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true) shouldBe
                MergeResult.AlreadyUpToDate
            fixture.gateway.rebase(RefName(FEATURE_BRANCH)) shouldBe RebaseResult.AlreadyUpToDate

            fixture.head() shouldBe root
            fixture.parentCountOfHead() shouldBe 0
        }
    }

    test("HEAD 가 병합 커밋인 저장소에서도 다음 병합이 이어진다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenDivergedBranches()
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            val mergeCommit = fixture.head()
            fixture.parentCountOfHead() shouldBe 2
            fixture.branch(OTHER_BRANCH)
            fixture.checkout(OTHER_BRANCH)
            fixture.commit(OTHER_ONLY_FILE, "other only\n", "other only")
            fixture.checkout(INITIAL_BRANCH)
            val mainTip = fixture.commit(MAIN_SECOND_FILE, "main second\n", "main second")

            val result = fixture.gateway.merge(RefName(OTHER_BRANCH), allowFastForward = true)

            result.shouldBeInstanceOf<MergeResult.Succeeded>().fastForward shouldBe false
            fixture.parentCountOfHead() shouldBe 2
            fixture.firstParentOfHead() shouldBe mainTip
            fixture.head() shouldNotBe mergeCommit
            fixture.exists(OTHER_ONLY_FILE) shouldBe true
        }
    }

    test("공통 조상이 없는 고아 브랜치 병합은 충돌 없이 두 이력을 합친다") {
        openFixture(tempdir()).use { fixture ->
            fixture.commit(SHARED_FILE, "base\n", "base")
            fixture.orphan(ORPHAN_BRANCH)
            fixture.commit(ORPHAN_FILE, "orphan\n", "orphan")
            fixture.checkout(INITIAL_BRANCH)

            val result = fixture.gateway.merge(RefName(ORPHAN_BRANCH), allowFastForward = true)

            result.shouldBeInstanceOf<MergeResult.Succeeded>().fastForward shouldBe false
            fixture.parentCountOfHead() shouldBe 2
            fixture.exists(SHARED_FILE) shouldBe true
            fixture.exists(ORPHAN_FILE) shouldBe true
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("detached HEAD 에서 병합이 충돌해도 중단하면 그 커밋으로 돌아온다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            val detachedAt = fixture.head()
            fixture.checkout(detachedAt.name)

            fixture.gateway.repositoryState() shouldBe RepositoryState.DETACHED

            val result = fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            result.shouldBeInstanceOf<MergeResult.Conflicted>().paths shouldContainExactly listOf(SHARED_FILE)
            fixture.gateway.repositoryState() shouldBe RepositoryState.MERGING

            fixture.gateway.abortMerge(fixture.abortConfirmation())

            fixture.head() shouldBe detachedAt
            fixture.read(SHARED_FILE) shouldBe "main\n"
            fixture.gateway.repositoryState() shouldBe RepositoryState.DETACHED
        }
    }

    test("Gateway 호출은 GitAccess 경계 안에서 직렬화된다") {
        openFixture(tempdir()).use { fixture ->
            fixture.commit(SHARED_FILE, "base\n", "base")
            val insideLock = CountDownLatch(1)
            val releaseLock = CountDownLatch(1)

            coroutineScope {
                val holder = launch(Dispatchers.IO) {
                    fixture.gitAccess.withRepository {
                        insideLock.countDown()
                        releaseLock.await()
                    }
                }
                insideLock.await()
                val probe = async(Dispatchers.IO) { fixture.gateway.repositoryState() }
                delay(LOCK_PROBE_MILLIS)

                // 자기 락을 따로 쓴다면 여기서 이미 끝나 있다 — GitAccess 를 통과한다는 증거다.
                probe.isCompleted shouldBe false
                releaseLock.countDown()
                probe.await() shouldBe RepositoryState.NORMAL
                holder.join()
            }
        }
    }

    test("진행 중이 아닌데 abort 를 직접 호출하면 ORIG_HEAD 로 되돌리지 않는다") {
        openFixture(tempdir()).use { fixture ->
            // merge 를 끝내 ORIG_HEAD 는 남아 있지만 상태는 NORMAL 인 저장소.
            fixture.givenConflictingBranches()
            fixture.checkout(INITIAL_BRANCH)
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            fixture.write(SHARED_FILE, "resolved\n")
            fixture.stage(SHARED_FILE)
            fixture.gateway.continueMerge()
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
            val headBefore = fixture.head()

            // 서비스를 거치지 않고 부르면 상태 검사가 없던 시절엔 끝난 작업까지 되돌아갔다.
            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.abortMerge(fixture.abortConfirmation())
            }
            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.abortRebase(fixture.abortConfirmation())
            }

            fixture.head() shouldBe headBefore
        }
    }

    test("리베이스 중 abortMerge 를 직접 부르면 거부하고 진행 중 상태를 그대로 둔다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(FEATURE_BRANCH)
            fixture.gateway.rebase(RefName(INITIAL_BRANCH))
            val headBefore = fixture.head()
            val conflictedContent = fixture.read(SHARED_FILE)
            val conflictedPaths = fixture.conflictingPaths()

            // 반대 연산의 abort 가 통과하면 리베이스 중인 저장소를 병합 복구 경로로 되돌리게 된다.
            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.abortMerge(fixture.abortConfirmation())
            }

            fixture.head() shouldBe headBefore
            fixture.gateway.repositoryState() shouldBe RepositoryState.REBASING
            // 충돌 해결 중이던 워킹트리와 충돌 목록도 그대로여야 한다 — 거부는 아무것도 건드리지 않는다.
            fixture.read(SHARED_FILE) shouldBe conflictedContent
            fixture.conflictingPaths() shouldBe conflictedPaths
        }
    }

    test("병합 중 abortRebase 를 직접 부르면 거부하고 진행 중 상태를 그대로 둔다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(INITIAL_BRANCH)
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            val headBefore = fixture.head()
            val conflictedContent = fixture.read(SHARED_FILE)
            val conflictedPaths = fixture.conflictingPaths()

            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.abortRebase(fixture.abortConfirmation())
            }

            fixture.head() shouldBe headBefore
            fixture.gateway.repositoryState() shouldBe RepositoryState.MERGING
            fixture.read(SHARED_FILE) shouldBe conflictedContent
            fixture.conflictingPaths() shouldBe conflictedPaths
        }
    }

    test("두 Gateway 호출이 동시에 들어와도 Repository 접근이 겹치지 않는다") {
        openFixture(tempdir()).use { fixture ->
            fixture.commit(SHARED_FILE, "main\n", "initial")
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)

            coroutineScope {
                // Gateway 호출 하나가 임계구역을 쥔 사이 다른 Gateway 호출이 들어온다.
                val holder = launch(Dispatchers.IO) {
                    fixture.gitAccess.withRepository {
                        started.countDown()
                        release.await()
                    }
                }
                started.await()
                val first = async(Dispatchers.IO) { fixture.gateway.repositoryState() }
                val second = async(Dispatchers.IO) { fixture.gateway.rebasingCommit() }
                delay(LOCK_PROBE_MILLIS)

                // 둘 다 같은 경계에서 기다린다 — 어느 쪽도 자기 락으로 빠져나가지 않는다.
                first.isCompleted shouldBe false
                second.isCompleted shouldBe false
                release.countDown()
                first.await() shouldBe RepositoryState.NORMAL
                second.await() shouldBe null
                holder.join()
            }
        }
    }

    test("구현은 자기 락이나 디스패처를 두지 않는다 — 직렬화 경계는 GitAccess 하나다") {
        val sources = File(IMPLEMENTATION_SOURCE_PATH).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        sources.isEmpty() shouldBe false
        sources.flatMap { source ->
            source.readLines().filter { line ->
                line.contains("Mutex") || line.contains("withContext") || line.contains("Dispatchers")
            }
        } shouldContainExactly emptyList()
    }

    test("병합이 진행 중이면 새 병합·리베이스를 시작하지 않고 ORIG_HEAD 를 지킨다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(INITIAL_BRANCH)
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            fixture.gateway.repositoryState() shouldBe RepositoryState.MERGING
            val startPointBefore = fixture.origHead()

            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            }
            shouldThrow<UndineException.StateViolation> { fixture.gateway.rebase(RefName(INITIAL_BRANCH)) }

            // ORIG_HEAD 가 부분 진행 HEAD 로 덮어써지면 abort 가 되돌릴 지점을 잃는다.
            fixture.origHead() shouldBe startPointBefore
            fixture.gateway.repositoryState() shouldBe RepositoryState.MERGING
        }
    }

    test("리베이스가 진행 중이면 새 병합·리베이스를 시작하지 않고 ORIG_HEAD 를 지킨다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(FEATURE_BRANCH)
            fixture.gateway.rebase(RefName(INITIAL_BRANCH))
            fixture.gateway.repositoryState() shouldBe RepositoryState.REBASING
            val startPointBefore = fixture.origHead()

            shouldThrow<UndineException.StateViolation> { fixture.gateway.rebase(RefName(INITIAL_BRANCH)) }
            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.merge(RefName(INITIAL_BRANCH), allowFastForward = true)
            }

            fixture.origHead() shouldBe startPointBefore
            fixture.gateway.repositoryState() shouldBe RepositoryState.REBASING
        }
    }

    test("진행 중 시작을 거부한 뒤 abort 하면 원래 시작 지점으로 복구된다") {
        openFixture(tempdir()).use { fixture ->
            fixture.givenConflictingBranches()
            fixture.checkout(INITIAL_BRANCH)
            val headBeforeMerge = fixture.head()
            fixture.gateway.merge(RefName(FEATURE_BRANCH), allowFastForward = true)

            // 덮어쓰기가 없었음을 abort 결과로 증명한다 — 지점이 망가졌다면 여기서 다른 커밋으로 간다.
            shouldThrow<UndineException.StateViolation> { fixture.gateway.rebase(RefName(INITIAL_BRANCH)) }
            fixture.gateway.abortMerge(fixture.abortConfirmation())

            fixture.head() shouldBe headBeforeMerge
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

    test("continueMerge·continueRebase·skipRebaseCommit 은 진행 중이 아니면 저장소를 건드리지 않는다") {
        openFixture(tempdir()).use { fixture ->
            fixture.commit(SHARED_FILE, "main\n", "initial")
            fixture.write(SHARED_FILE, "staged edit\n")
            fixture.stage(SHARED_FILE)
            val headBefore = fixture.head()

            // NORMAL 에서 continueMerge 가 통과하면 staged 변경이 일반 커밋으로 나간다.
            shouldThrow<UndineException.StateViolation> { fixture.gateway.continueMerge() }
            shouldThrow<UndineException.StateViolation> { fixture.gateway.continueRebase() }
            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.skipRebaseCommit(fixture.skipConfirmation())
            }

            fixture.head() shouldBe headBefore
            fixture.read(SHARED_FILE) shouldBe "staged edit\n"
            fixture.gateway.repositoryState() shouldBe RepositoryState.NORMAL
        }
    }

})
