package dev.undine.infrastructure.git.worktreeops

import dev.undine.domain.BranchOperation
import dev.undine.domain.BranchOperationResult
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryHolder
import dev.undine.infrastructure.git.repository.commitFile
import dev.undine.infrastructure.git.repository.initRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

private const val BASE_FILE = "base.txt"
private const val MAIN_FILE = "main.txt"
private const val FEATURE_FILE = "feature.txt"
private const val MAIN_REF = "refs/heads/main"
private const val FEATURE_REF = "refs/heads/feature"
private const val MISSING_COMMIT = "0123456789abcdef0123456789abcdef01234567"
private const val DIRTY_EDIT = "커밋하지 않은 편집\n"

/** 임계 구역을 먼저 점유해, 뒤따르는 두 요청이 대기 순서대로 줄서게 만드는 시간. */
private const val HOLD_MILLIS = 300L

/** 조작 요청이 체크아웃 요청보다 먼저 대기 줄에 서는 것을 보장하는 간격. */
private const val QUEUE_MILLIS = 60L

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val MISSING = RefName("missing")

/**
 * `main` 과 `feature` 가 **서로 다른 파일**을 더해 갈라진 저장소. 병합이 충돌 없이 끝난다.
 * 반환 시점의 HEAD 는 `main` 이다.
 */
private class BranchSequenceFixture(val git: Git) {

    val base: CommitId
    val featureHead: CommitId
    val mainHead: CommitId

    init {
        git.repository.config.apply {
            setString("user", null, "name", "Undine Test")
            setString("user", null, "email", "test@undine.dev")
            save()
        }
        base = CommitId.of(git.commitFile(BASE_FILE, "base\n", "base").name)
        git.checkout().setName(FEATURE.value).setCreateBranch(true).call()
        featureHead = CommitId.of(git.commitFile(FEATURE_FILE, "feature\n", "feature").name)
        git.checkout().setName(MAIN.value).call()
        mainHead = CommitId.of(git.commitFile(MAIN_FILE, "main\n", "main").name)
    }

    val gitAccess = GitAccess(RepositoryHolder { git.repository })
    val gateway: WorktreeOpsGatewayImpl = WorktreeOpsGatewayImpl(gitAccess)
    val refGateway: RefGatewayImpl = RefGatewayImpl(gitAccess)

    suspend fun open() {
        gitAccess.open(RepositoryPath(git.repository.workTree.path)) { }
    }

    fun currentRef(): String? = git.repository.fullBranch

    fun targetOf(fullRef: String): String = git.repository.exactRef(fullRef).objectId.name

    fun exists(name: String): Boolean = File(git.repository.workTree, name).exists()

    fun readFile(name: String): String = File(git.repository.workTree, name).readText()

    /** 추적 중인 파일을 커밋하지 않고 고쳐 워킹트리를 더티로 만든다. */
    fun editWithoutCommit(name: String, content: String) {
        File(git.repository.workTree, name).writeText(content)
    }

    /**
     * [branch] 에서 [BASE_FILE] 을 [content] 로 고쳐 커밋하고, HEAD 를 원래 자리로 돌려놓는다.
     * 두 브랜치에 각각 부르면 같은 파일을 서로 다르게 고쳐 병합이 충돌하는 저장소가 된다.
     */
    fun divergeOn(branch: RefName, content: String): CommitId {
        val before = git.repository.fullBranch
        git.checkout().setName(branch.value).call()
        val commit = CommitId.of(git.commitFile(BASE_FILE, content, "diverge ${branch.value}").name)
        git.checkout().setName(before).call()
        return commit
    }
}

/**
 * 변경과 그 결과의 소비를 한 [NonCancellable] 단위로 묶은 **계약을 지키는 호출자**를 흉내 내고,
 * 그 구간 안에 취소를 주입한다. 소비된 값을 돌려준다.
 *
 * 취소 시점을 "시퀀스에 들어간 뒤" 로 고정해 흔들림 없이 재현한다 — 임계 구역을 먼저 점유해
 * 호출이 대기 줄에 서게 만든 뒤 취소한다. 이렇게 들어온 취소가 조작을 중간에 끊거나 소비를
 * 건너뛰면, 저장소는 바뀌었는데 되돌릴 방법이 없는 상태가 남는다 (결정 A-L2·G4).
 */
private suspend fun <T> BranchSequenceFixture.consumedDespiteCancellation(call: suspend () -> T): T? {
    val consumed = AtomicReference<T?>(null)
    coroutineScope {
        val holderEntered = CompletableDeferred<Unit>()
        launch(Dispatchers.Default) {
            gitAccess.withSequence {
                holderEntered.complete(Unit)
                Thread.sleep(HOLD_MILLIS)
            }
        }
        holderEntered.await()
        val caller = launch(Dispatchers.Default) {
            withContext(NonCancellable) { consumed.set(call()) }
        }
        // 호출이 임계 구역 대기에 들어간 뒤에 취소한다 — 그 전이면 시작 자체를 막는 경로가 된다.
        delay(QUEUE_MILLIS)
        caller.cancel()
        caller.join()
    }
    return consumed.get()
}

/**
 * 브랜치 대상 조작이 **한 임계 구역**에서 끝나는지, ref 이동이 조건부 갱신인지를 실제 임시 저장소로 본다.
 *
 * 시퀀스 전체를 감싸는 잠금 자체는 `GitAccessSpec` 이 본다 — 여기서는 그 위에 세운 계약
 * (수행 브랜치 반환·실패 시 복귀·detached 거부·reset 의 HEAD 판정)을 검증한다.
 */
class BranchSequenceSpec : FunSpec({

    val openedRepositories = mutableListOf<Git>()

    afterTest {
        openedRepositories.forEach(Git::close)
        openedRepositories.clear()
    }

    suspend fun fixture(): BranchSequenceFixture {
        val git = initRepository(tempdir()).also { openedRepositories += it }
        return BranchSequenceFixture(git).also { it.open() }
    }

    test("대상 브랜치를 체크아웃한 뒤 병합하고 결과에 실제 수행 브랜치가 담긴다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.Merge(source = MAIN, allowFastForward = true),
        )

        result.performedOn shouldBe FEATURE
        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        fixture.currentRef() shouldBe FEATURE_REF
        fixture.exists(MAIN_FILE) shouldBe true
        fixture.exists(FEATURE_FILE) shouldBe true
    }

    test("직렬로 이어진 두 조작에서 첫 결과의 기준 상태는 두 번째 조작을 포함하지 않는다") {
        val fixture = fixture()

        // 두 조작을 결정적으로 직렬 배치한다 — 두 번째는 수행 브랜치도 HEAD 도 바꾼다.
        val first = fixture.gateway.runOnBranch(
            BranchTarget.Named(MAIN),
            BranchOperation.Merge(source = FEATURE, allowFastForward = true),
        )
        val second = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.Merge(source = MAIN, allowFastForward = true),
        )

        val firstSucceeded = first.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        val secondSucceeded = second.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        // 첫 결과는 자기 임계 구역 안에서 캡처됐으므로 두 번째 조작의 체크아웃을 알지 못한다 (UND-73).
        firstSucceeded.baseline shouldBe RepositoryBaseline(branch = MAIN, head = firstSucceeded.head)
        secondSucceeded.baseline shouldBe RepositoryBaseline(branch = FEATURE, head = secondSucceeded.head)
        fixture.currentRef() shouldBe FEATURE_REF
    }

    test("현재 브랜치를 조건부 reset 하면 결과 기준 상태가 옮겨진 자리를 가리킨다") {
        val fixture = fixture()

        val baseline = fixture.gateway.hardResetBranch(MAIN, to = fixture.base, expected = fixture.mainHead)

        baseline shouldBe RepositoryBaseline(branch = MAIN, head = fixture.base)
        fixture.targetOf(MAIN_REF) shouldBe fixture.base.value
    }

    test("체크아웃되지 않은 브랜치를 reset 해도 기준 상태는 실제 HEAD 를 가리킨다") {
        val fixture = fixture()

        val baseline = fixture.gateway.hardResetBranch(FEATURE, to = fixture.base, expected = fixture.featureHead)

        // HEAD 는 main 그대로다 — 되돌리기 비교의 기준은 옮긴 브랜치가 아니라 체크아웃된 브랜치다.
        baseline shouldBe RepositoryBaseline(branch = MAIN, head = fixture.mainHead)
        fixture.targetOf(FEATURE_REF) shouldBe fixture.base.value
    }

    test("대상 브랜치에서 cherry-pick 하면 그 브랜치에 새 커밋이 생긴다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.CherryPick(commit = fixture.mainHead, recordOrigin = false),
        )

        result.performedOn shouldBe FEATURE
        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        fixture.targetOf(FEATURE_REF) shouldNotBe fixture.featureHead.value
    }

    test("대상 브랜치를 rebase 하면 그 브랜치가 upstream 위로 재배치된다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.Rebase(upstream = MAIN),
        )

        result.performedOn shouldBe FEATURE
        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        fixture.currentRef() shouldBe FEATURE_REF
        fixture.targetOf(FEATURE_REF) shouldNotBe fixture.featureHead.value
        fixture.exists(MAIN_FILE) shouldBe true
        fixture.exists(FEATURE_FILE) shouldBe true
    }

    test("조작이 도는 동안 들어온 실제 체크아웃 요청은 조작이 끝난 뒤에 실행된다") {
        val fixture = fixture()
        val order = CopyOnWriteArrayList<String>()
        val holderEntered = CompletableDeferred<Unit>()

        val result = coroutineScope {
            // 임계 구역을 먼저 점유해 두 요청이 대기 줄에 서게 한다 — 줄 순서가 곧 실행 순서다.
            launch(Dispatchers.Default) {
                fixture.gitAccess.withSequence {
                    holderEntered.complete(Unit)
                    Thread.sleep(HOLD_MILLIS)
                }
            }
            holderEntered.await()

            val operation = async(Dispatchers.Default) {
                fixture.gateway.runOnBranch(
                    BranchTarget.Named(FEATURE),
                    BranchOperation.Merge(source = MAIN, allowFastForward = true),
                ).also { order += "operation" }
            }
            delay(QUEUE_MILLIS)
            launch(Dispatchers.Default) {
                fixture.refGateway.checkout(MAIN, force = false)
                order += "checkout"
            }
            operation.await()
        }

        order.toList() shouldBe listOf("operation", "checkout")
        result.performedOn shouldBe FEATURE
        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        // 끼어든 체크아웃이 조작 중간에 들어왔다면 병합은 main 에서 돌았을 것이다.
        fixture.targetOf(FEATURE_REF) shouldNotBe fixture.featureHead.value
        fixture.targetOf(MAIN_REF) shouldBe fixture.mainHead.value
        fixture.currentRef() shouldBe MAIN_REF
    }

    test("조작이 실패하면 호출 전 HEAD 로 돌아오고 워킹트리에 흔적이 남지 않는다") {
        val fixture = fixture()

        shouldThrow<UndineException.NotFound> {
            fixture.gateway.runOnBranch(
                BranchTarget.Named(FEATURE),
                BranchOperation.CherryPick(commit = CommitId.of(MISSING_COMMIT), recordOrigin = false),
            )
        }

        fixture.currentRef() shouldBe MAIN_REF
        fixture.targetOf(FEATURE_REF) shouldBe fixture.featureHead.value
        fixture.exists(MAIN_FILE) shouldBe true
        fixture.exists(FEATURE_FILE) shouldBe false
    }

    test("현재 브랜치 대상이라도 커밋하지 않은 편집이 있으면 시작하지 않고 그 편집을 보존한다") {
        val fixture = fixture()
        fixture.editWithoutCommit(MAIN_FILE, DIRTY_EDIT)

        shouldThrow<UndineException.DirtyWorkingTree> {
            fixture.gateway.runOnBranch(
                BranchTarget.Current,
                // 없는 source — 시작 전 검증에서 실패해 복구 경로로 들어가던 입력이다.
                BranchOperation.Merge(source = MISSING, allowFastForward = true),
            )
        }

        fixture.readFile(MAIN_FILE) shouldBe DIRTY_EDIT
        fixture.currentRef() shouldBe MAIN_REF
        fixture.targetOf(MAIN_REF) shouldBe fixture.mainHead.value
    }

    test("커밋하지 않은 편집이 있으면 rebase 도 시작하지 않는다") {
        val fixture = fixture()
        fixture.editWithoutCommit(MAIN_FILE, DIRTY_EDIT)

        shouldThrow<UndineException.DirtyWorkingTree> {
            fixture.gateway.runOnBranch(BranchTarget.Current, BranchOperation.Rebase(upstream = MISSING))
        }

        fixture.readFile(MAIN_FILE) shouldBe DIRTY_EDIT
        fixture.targetOf(MAIN_REF) shouldBe fixture.mainHead.value
    }

    test("커밋하지 않은 편집이 있으면 없는 커밋 cherry-pick 도 그 편집을 지우지 않는다") {
        val fixture = fixture()
        fixture.editWithoutCommit(MAIN_FILE, DIRTY_EDIT)

        shouldThrow<UndineException.DirtyWorkingTree> {
            fixture.gateway.runOnBranch(
                BranchTarget.Current,
                BranchOperation.CherryPick(commit = CommitId.of(MISSING_COMMIT), recordOrigin = false),
            )
        }

        fixture.readFile(MAIN_FILE) shouldBe DIRTY_EDIT
        fixture.currentRef() shouldBe MAIN_REF
        fixture.targetOf(MAIN_REF) shouldBe fixture.mainHead.value
    }

    test("detached HEAD 에서 현재 브랜치 대상 연산을 요청하면 사유와 함께 거부한다") {
        val fixture = fixture()
        fixture.git.checkout().setName(fixture.base.value).call()

        val failure = shouldThrow<UndineException.StateViolation> {
            fixture.gateway.runOnBranch(
                BranchTarget.Current,
                BranchOperation.Merge(source = MAIN, allowFastForward = true),
            )
        }

        failure.detail shouldContain "detached"
    }

    test("현재 브랜치 대상 연산은 체크아웃된 브랜치에서 수행된다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Current,
            BranchOperation.Merge(source = FEATURE, allowFastForward = true),
        )

        result.performedOn shouldBe MAIN
        fixture.currentRef() shouldBe MAIN_REF
    }

    test("병합이 성공하면 previousTarget 이 조작 직전 대상 브랜치 위치와 같다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.Merge(source = MAIN, allowFastForward = true),
        )

        result.previousTarget shouldBe fixture.featureHead
        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        result.head shouldNotBe fixture.featureHead
    }

    test("리베이스가 성공해도 previousTarget 은 조작 전 대상 브랜치 위치다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.Rebase(upstream = MAIN),
        )

        result.previousTarget shouldBe fixture.featureHead
        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        result.head shouldNotBe fixture.featureHead
    }

    test("cherry-pick 이 성공해도 previousTarget 은 조작 전 대상 브랜치 위치다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.CherryPick(commit = fixture.mainHead, recordOrigin = false),
        )

        result.previousTarget shouldBe fixture.featureHead
        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
    }

    test("적용할 변경이 없어 NoChange 일 때도 previousTarget 이 담긴다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            // 자기 자신을 병합하면 적용할 변경이 없다.
            BranchOperation.Merge(source = FEATURE, allowFastForward = true),
        )

        result.shouldBeInstanceOf<BranchOperationResult.NoChange>()
        result.previousTarget shouldBe fixture.featureHead
        fixture.targetOf(FEATURE_REF) shouldBe fixture.featureHead.value
    }

    test("충돌로 멈춘 Conflicted 결과에도 previousTarget 이 담긴다") {
        val fixture = fixture()
        val featureConflict = fixture.divergeOn(FEATURE, "feature 쪽 편집\n")
        fixture.divergeOn(MAIN, "main 쪽 편집\n")

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.Merge(source = MAIN, allowFastForward = true),
        )

        result.shouldBeInstanceOf<BranchOperationResult.Conflicted>()
        result.paths shouldBe listOf(BASE_FILE)
        result.previousTarget shouldBe featureConflict
    }

    test("previousTarget 으로 구성한 되돌리기가 조작 전 위치를 정확히 복원한다") {
        val fixture = fixture()

        val result = fixture.gateway.runOnBranch(
            BranchTarget.Named(FEATURE),
            BranchOperation.Merge(source = MAIN, allowFastForward = true),
        )
        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        fixture.gateway.hardResetBranch(FEATURE, to = result.previousTarget, expected = result.head)

        fixture.targetOf(FEATURE_REF) shouldBe fixture.featureHead.value
        fixture.exists(FEATURE_FILE) shouldBe true
        fixture.exists(MAIN_FILE) shouldBe false
    }

    test("reset 대상이 실행 시점의 실제 현재 브랜치면 워킹트리를 동기화한다") {
        val fixture = fixture()

        fixture.gateway.hardResetBranch(MAIN, to = fixture.base, expected = fixture.mainHead)

        fixture.targetOf(MAIN_REF) shouldBe fixture.base.value
        fixture.exists(MAIN_FILE) shouldBe false
        fixture.exists(BASE_FILE) shouldBe true
    }

    test("reset 대상이 현재 브랜치가 아니면 워킹트리를 바꾸지 않고 ref 만 옮긴다") {
        val fixture = fixture()

        fixture.gateway.hardResetBranch(FEATURE, to = fixture.base, expected = fixture.featureHead)

        fixture.targetOf(FEATURE_REF) shouldBe fixture.base.value
        fixture.currentRef() shouldBe MAIN_REF
        fixture.exists(MAIN_FILE) shouldBe true
        fixture.exists(FEATURE_FILE) shouldBe false
    }

    test("병합 도중 취소가 들어와도 조작이 끝나고 결과가 소비 구간까지 도달한다") {
        val fixture = fixture()

        val result = fixture.consumedDespiteCancellation {
            fixture.gateway.runOnBranch(
                BranchTarget.Named(FEATURE),
                BranchOperation.Merge(source = MAIN, allowFastForward = true),
            )
        }

        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        result.performedOn shouldBe FEATURE
        fixture.exists(MAIN_FILE) shouldBe true
        fixture.exists(FEATURE_FILE) shouldBe true
    }

    test("rebase 도중 취소가 들어와도 조작이 끝나고 결과가 소비 구간까지 도달한다") {
        val fixture = fixture()

        val result = fixture.consumedDespiteCancellation {
            fixture.gateway.runOnBranch(BranchTarget.Named(FEATURE), BranchOperation.Rebase(upstream = MAIN))
        }

        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        result.performedOn shouldBe FEATURE
        fixture.targetOf(FEATURE_REF) shouldNotBe fixture.featureHead.value
    }

    test("cherry-pick 도중 취소가 들어와도 조작이 끝나고 결과가 소비 구간까지 도달한다") {
        val fixture = fixture()

        val result = fixture.consumedDespiteCancellation {
            fixture.gateway.runOnBranch(
                BranchTarget.Named(FEATURE),
                BranchOperation.CherryPick(commit = fixture.mainHead, recordOrigin = false),
            )
        }

        result.shouldBeInstanceOf<BranchOperationResult.Succeeded>()
        result.performedOn shouldBe FEATURE
        fixture.targetOf(FEATURE_REF) shouldNotBe fixture.featureHead.value
    }

    test("시퀀스가 시작되기 전에 떨어진 취소는 저장소를 건드리지 않는다") {
        val fixture = fixture()

        coroutineScope {
            val holderEntered = CompletableDeferred<Unit>()
            launch(Dispatchers.Default) {
                fixture.gitAccess.withSequence {
                    holderEntered.complete(Unit)
                    Thread.sleep(HOLD_MILLIS)
                }
            }
            holderEntered.await()
            // 소비 구간으로 감싸지 않은 호출은 대기 중 취소되고, 그 시점엔 아직 아무것도 바뀌지 않았다.
            val caller = launch(Dispatchers.Default) {
                fixture.gateway.hardResetBranch(MAIN, to = fixture.base, expected = fixture.mainHead)
            }
            delay(QUEUE_MILLIS)
            caller.cancel()
            caller.join()
        }

        fixture.targetOf(MAIN_REF) shouldBe fixture.mainHead.value
        fixture.exists(MAIN_FILE) shouldBe true
    }

    test("reset 도중 취소가 들어와도 ref 이동과 워킹트리 동기화가 끝난다") {
        val fixture = fixture()

        fixture.consumedDespiteCancellation {
            fixture.gateway.hardResetBranch(MAIN, to = fixture.base, expected = fixture.mainHead)
        }

        fixture.targetOf(MAIN_REF) shouldBe fixture.base.value
        fixture.exists(MAIN_FILE) shouldBe false
        fixture.exists(BASE_FILE) shouldBe true
    }

    test("reset 도 기대 위치가 어긋나면 ref 를 그대로 두고 거부한다") {
        val fixture = fixture()

        val failure = shouldThrow<UndineException.StateViolation> {
            fixture.gateway.hardResetBranch(FEATURE, to = fixture.base, expected = fixture.mainHead)
        }

        failure.detail shouldContain FEATURE.value
        fixture.targetOf(FEATURE_REF) shouldBe fixture.featureHead.value
        fixture.exists(MAIN_FILE) shouldBe true
    }
})
