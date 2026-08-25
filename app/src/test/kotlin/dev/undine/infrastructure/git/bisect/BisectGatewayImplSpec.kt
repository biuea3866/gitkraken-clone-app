package dev.undine.infrastructure.git.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectService
import dev.undine.domain.bisect.BisectSession
import dev.undine.domain.bisect.BisectStartPoint
import dev.undine.domain.bisect.BisectUnsupportedReason
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.domain.bisect.CandidateSurvey
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.toOpenedRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

private const val MAIN = "main"
private const val SIDE = "side"

/** git 이 남기지만 이 앱은 쓰지 않는 상태 파일. 삭제 실패를 재현하는 자리로 쓴다. */
private const val BISECT_NAMES = "BISECT_NAMES"

/** 진행 표식. 기록 실패를 재현하는 자리다. */
private const val BISECT_LOG = "BISECT_LOG"

/** 되돌릴 자리의 근거. 어떤 실패 뒤에도 남아 있어야 reset 을 다시 시도할 수 있다. */
private const val BISECT_START = "BISECT_START"

/** `--no-checkout` 세션의 검사 대상 참조. 이 앱은 쓰지 않지만 외부 CLI 는 남긴다. */
private const val BISECT_HEAD = "BISECT_HEAD"

/** 외부 git CLI 가 `run`·`--no-checkout` 으로 이어갈 때 남기는 표준 상태 파일. */
private val EXTERNAL_STATE_FILES = listOf(
    "BISECT_RUN",
    "BISECT_RESET_WHEN_FOUND",
    "BISECT_ANCESTORS_OK",
    "BISECT_FIRST_PARENT",
)

private const val CODE = "code.txt"
private const val OTHER = "other.txt"

/** 진행 중인 연산이 남기는 자리. 새 세션이 그 위를 덮지 않았는지 보는 근거다. */
private const val MERGE_HEAD = "MERGE_HEAD"
private const val CHERRY_PICK_HEAD = "CHERRY_PICK_HEAD"
private const val REVERT_HEAD = "REVERT_HEAD"
private const val REBASE_MERGE_DIR = "rebase-merge"

/** 커밋 시각을 고정한다 — 실행 시각에 따라 순서가 흔들리면 후보 계산을 검증할 수 없다. */
private val AUTHOR = PersonIdent(
    "Undine Test",
    "test@undine.dev",
    Instant.parse("2026-01-02T03:04:05Z"),
    ZoneOffset.UTC,
)

/**
 * bisect Gateway — **실제 임시 저장소**로 검증한다.
 *
 * 세션 상태가 `.git/` 표준 위치에 남는지, 체크아웃과 복구가 실제로 일어나는지는 Mock 으로 아무것도
 * 확인하지 못한다 ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1).
 */
class BisectGatewayImplSpec : FunSpec({

    test("시작하면 good 과 bad 사이의 중간 커밋이 체크아웃된다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)

        val result = withBisect(work) { service ->
            service.start(good = commits[0], bad = commits[7])
        }

        // 후보 (c1, c8] 7건, 검사 대상 6건의 가운데인 c5 다.
        val testing = result.shouldBeInstanceOf<BisectResult.Testing>()
        testing.commit shouldBe commits[4]
        testing.remainingCandidates shouldBe 7
        testing.expectedRemainingChecks shouldBe 3
        headCommit(work) shouldBe commits[4]
    }

    test("bisect 중인 저장소는 DETACHED 가 아니라 BISECTING 으로 노출된다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }

        // 검사 대상에 detached 로 붙어 있지만, 화면이 continue/reset 을 안내하려면 구분돼야 한다.
        Git.open(work).use { git ->
            val opened = git.repository.toOpenedRepository()
            opened.state shouldBe RepositoryState.BISECTING
            opened.currentBranch shouldBe null
        }
    }

    test("good/bad 를 반복 판정하면 최초 나쁜 커밋으로 확정된다") {
        val work = tempdir()
        // 원인은 c4 다 — 인덱스 3 이상이 bad.
        val commits = seedLinearHistory(work, count = 8)

        val result = withBisect(work) { service ->
            var step = service.start(good = commits[0], bad = commits[7])
            while (step is BisectResult.Testing) {
                val verdict = if (commits.indexOf(step.commit) >= 3) BisectVerdict.BAD else BisectVerdict.GOOD
                step = service.mark(verdict)
            }
            step
        }

        result shouldBe BisectResult.FirstBad(commits[3])
    }

    test("good 과 bad 사이에 커밋이 없으면 검사 없이 즉시 확정된다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 3)

        val result = withBisect(work) { service ->
            service.start(good = commits[1], bad = commits[2])
        }

        result shouldBe BisectResult.FirstBad(commits[2])
        // 검사 대상이 없으므로 체크아웃도 일어나지 않는다.
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
    }

    test("bad 가 good 의 조상이면 역방향 경고를 돌려주고 세션을 만들지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 5)

        val result = withBisect(work) { service ->
            service.start(good = commits[4], bad = commits[1])
        }

        result shouldBe BisectResult.ReversedRange(good = commits[4], bad = commits[1])
        bisectLog(work).exists() shouldBe false
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
    }

    test("skip 으로 좁히지 못하면 후보 목록을 돌려준다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 3)

        val result = withBisect(work) { service ->
            service.start(good = commits[0], bad = commits[2])
            // 후보 (c1, c3] 에서 검사 대상은 c2 하나뿐이다.
            service.mark(BisectVerdict.SKIP)
        }

        result.shouldBeInstanceOf<BisectResult.Inconclusive>()
            .candidates shouldContainExactly listOf(commits[1], commits[2])
    }

    test("진행 중 세션이 새 Gateway 인스턴스에서 그대로 복원된다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }

        // 앱을 껐다 켠 상황 — 새 GitAccess·새 Gateway 로 저장소만 보고 이어간다.
        val restored = withBisect(work) { service -> service.currentSession() }

        restored?.startPoint shouldBe BisectStartPoint.Branch(RefName(Constants.R_HEADS + MAIN))
        restored?.good shouldContainExactly listOf(commits[0])
        restored?.bad shouldBe commits[7]
        restored?.testing shouldBe commits[4]
    }

    test("복원된 세션에서 판정을 이어가 확정까지 갈 수 있다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 4)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[3]) }

        val result = withBisect(work) { service ->
            var step: BisectResult = service.mark(BisectVerdict.BAD)
            while (step is BisectResult.Testing) {
                step = service.mark(BisectVerdict.BAD)
            }
            step
        }

        result.shouldBeInstanceOf<BisectResult.FirstBad>()
    }

    test("reset 하면 시작 브랜치로 돌아가고 bisect 상태가 사라진다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }

        withBisect(work) { service -> service.reset() }

        headBranch(work) shouldBe Constants.R_HEADS + MAIN
        headCommit(work) shouldBe commits[7]
        bisectLog(work).exists() shouldBe false
        bisectRefNames(work) shouldContainExactly emptyList()
        Git.open(work).use { git ->
            git.repository.toOpenedRepository().state shouldBe RepositoryState.NORMAL
        }
    }

    test("외부 도구가 남긴 no-checkout·run 상태와 packed 참조까지 reset 이 모두 지운다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }
        // 같은 저장소를 외부 git CLI 와 오간 상황이다. 우리가 쓴 것만 지우면 남은 표준 상태 때문에
        // git 은 이 저장소를 계속 bisect 중으로 본다 — 우리는 reset 했는데 git 은 아니라고 한다.
        writeRootRef(work, BISECT_HEAD, commits[4])
        EXTERNAL_STATE_FILES.forEach { name -> File(work, ".git/$name").writeText("x\n") }
        packRefs(work)

        withBisect(work) { service -> service.reset() }

        leftoverBisectState(work) shouldContainExactly emptyList()
        bisectRefNames(work) shouldContainExactly emptyList()
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
        Git.open(work).use { git ->
            git.repository.toOpenedRepository().state shouldBe RepositoryState.NORMAL
        }
        // 남은 상태가 하나라도 있으면 다음 시작이 "이미 진행 중" 으로 막힌다.
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }
            .shouldBeInstanceOf<BisectResult.Testing>()
    }

    test("detached HEAD 에서 시작했으면 reset 이 그 커밋으로 되돌린다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        // 브랜치가 아니라 커밋 위에서 시작한다 — 아무 브랜치로 돌려놓으면 있던 자리가 아니다.
        Git.open(work).use { git -> git.checkout().setName(commits[6].value).call() }

        withBisect(work) { service -> service.start(good = commits[0], bad = commits[6]) }
        val restored = withBisect(work) { service -> service.currentSession() }
        withBisect(work) { service -> service.reset() }

        restored?.startPoint shouldBe BisectStartPoint.Detached(commits[6])
        headBranch(work) shouldBe null
        headCommit(work) shouldBe commits[6]
        bisectLog(work).exists() shouldBe false
    }

    test("병합 커밋이 낀 구간은 후보를 고르지 않고 미지원으로 알린다") {
        val work = tempdir()
        val merged = seedMergedHistory(work)

        val result = withBisect(work) { service ->
            service.start(good = merged.base, bad = merged.head)
        }

        val unsupported = result.shouldBeInstanceOf<BisectResult.Unsupported>()
        unsupported.reason shouldBe BisectUnsupportedReason.MERGE_COMMIT_IN_RANGE
        unsupported.at shouldBe merged.mergeCommit
        // 근거 없는 후보를 고르지 않으므로 HEAD 는 움직이지 않는다.
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
    }

    test("미지원으로 멈춘 세션도 reset 으로 빠져나올 수 있다 — 실패 상태가 아니다") {
        val work = tempdir()
        val merged = seedMergedHistory(work)
        withBisect(work) { service -> service.start(good = merged.base, bad = merged.head) }

        // 세션이 남아 있어야 reset 이 가능하다 — 미지원은 실패가 아니다.
        withBisect(work) { service -> service.currentSession() }?.bad shouldBe merged.head
        withBisect(work) { service -> service.reset() }

        bisectLog(work).exists() shouldBe false
        bisectRefNames(work) shouldContainExactly emptyList()
    }

    test("미지원으로 멈춘 세션에서 skip 은 거부되지 않고 같은 사유를 다시 알린다") {
        val work = tempdir()
        val merged = seedMergedHistory(work)
        withBisect(work) { service -> service.start(good = merged.base, bad = merged.head) }

        // 검사 대상이 없다고 예외를 던지면 이 세션은 reset 밖에 길이 없는 실패 상태가 된다.
        val result = withBisect(work) { service -> service.mark(BisectVerdict.SKIP) }

        val unsupported = result.shouldBeInstanceOf<BisectResult.Unsupported>()
        unsupported.reason shouldBe BisectUnsupportedReason.MERGE_COMMIT_IN_RANGE
        unsupported.at shouldBe merged.mergeCommit
        // 없는 진행을 지어내지 않는다 — HEAD 도 세션도 그대로다.
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
        withBisect(work) { service -> service.currentSession() }?.bad shouldBe merged.head
    }

    test("bisect 참조가 사라진 반쪽 상태에서도 reset 으로 시작 지점에 돌아온다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }
        removeBisectRefs(work)

        // 세션을 통째로 읽지는 못하는 상태다. 그래도 시작 지점이 남아 있으면 빠져나갈 수 있어야 한다.
        withBisect(work) { service ->
            shouldThrow<UndineException.StateViolation> { service.currentSession() }
        }
        withBisect(work) { service -> service.reset() }

        headBranch(work) shouldBe Constants.R_HEADS + MAIN
        headCommit(work) shouldBe commits[7]
        bisectLog(work).exists() shouldBe false
    }

    test("상태 파일을 지우지 못하면 reset 을 성공으로 보고하지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }
        // 비어 있지 않은 디렉토리는 지울 수 없다 — 삭제 실패를 실제로 재현한다.
        val obstruction = File(work, ".git/$BISECT_NAMES/child")
        obstruction.parentFile.mkdirs()
        obstruction.writeText("x")

        withBisect(work) { service ->
            shouldThrow<UndineException.StateViolation> { service.reset() }
        }

        // 시작 지점은 남아 있어야 한다 — 막힌 자리를 치우고 다시 시도할 수 있어야 하기 때문이다.
        startPointFile(work).exists() shouldBe true
        obstruction.delete() shouldBe true
        withBisect(work) { service -> service.reset() }
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
        bisectLog(work).exists() shouldBe false
    }

    test("이어지지 않는 두 커밋은 후보 구간을 정의할 수 없어 미지원이다") {
        val work = tempdir()
        val unrelated = seedUnrelatedBranches(work)

        val result = withGateway(work) { gateway ->
            gateway.surveyCandidates(listOf(unrelated.first), unrelated.second)
        }

        result.shouldBeInstanceOf<CandidateSurvey.NotLinear>()
            .reason shouldBe BisectUnsupportedReason.GOOD_IS_NOT_ANCESTOR_OF_BAD
    }

    test("저장소에 없는 커밋으로 시작하면 부재로 실패한다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 3)
        val absent = CommitId.of("0".repeat(Constants.OBJECT_ID_STRING_LENGTH))

        val failure = withBisect(work) { service ->
            shouldThrow<UndineException.NotFound> { service.start(good = commits[0], bad = absent) }
        }

        failure.kind shouldBe UndineException.NotFound.Kind.COMMIT
    }

    test("진행 중이 아닌데 reset 하면 상태 위반으로 거부한다") {
        val work = tempdir()
        seedLinearHistory(work, count = 3)

        withBisect(work) { service ->
            shouldThrow<UndineException.StateViolation> { service.reset() }
        }
    }

    test("bad 없이 외부 도구가 시작한 세션은 감추지 않고 상태 위반으로 알린다") {
        val work = tempdir()
        seedLinearHistory(work, count = 3)
        // `git bisect start` 만 실행한 상태 — 이 앱이 이어갈 수 없다.
        startPointFile(work).writeText("$MAIN\n")
        bisectLog(work).writeText("git bisect start\n")

        withBisect(work) { service ->
            shouldThrow<UndineException.StateViolation> { service.currentSession() }
        }.detail shouldBe NO_BAD_COMMIT
    }

    test("판정으로 bad 가 좁혀지면 예전 bisect 참조가 남지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)

        withBisect(work) { service ->
            service.start(good = commits[0], bad = commits[7])
            service.mark(BisectVerdict.BAD)
        }

        // bad 가 c5 로 좁혀졌다 — c8 을 가리키던 참조가 남아 있으면 다음 계산이 엉뚱해진다.
        bisectRefNames(work) shouldContainExactly listOf(
            "refs/bisect/bad",
            "refs/bisect/good-${commits[0].value}",
        )
        withBisect(work) { service -> service.currentSession() }?.bad shouldBe commits[4]
    }

    test("동시에 시작하면 하나만 세션을 만들고 나머지는 거절된다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)

        // 같은 저장소 핸들을 공유하는 두 호출이다 — 임계구역이 나뉜 사이에 서로 끼어들 수 있다.
        val outcomes = withSharedAccess(work) { access ->
            val first = BisectService(BisectGatewayImpl(access))
            val second = BisectService(BisectGatewayImpl(access))
            coroutineScope {
                val left = async(Dispatchers.Default) {
                    runCatching { first.start(good = commits[0], bad = commits[7]) }
                }
                val right = async(Dispatchers.Default) {
                    runCatching { second.start(good = commits[0], bad = commits[6]) }
                }
                listOf(left.await(), right.await())
            }
        }

        outcomes.count { outcome -> outcome.isSuccess } shouldBe 1
        outcomes.first { outcome -> outcome.isFailure }
            .exceptionOrNull().shouldBeInstanceOf<UndineException.StateViolation>()
        // 살아남은 세션과 HEAD 가 같은 커밋을 가리킨다 — 서로 덮어썼다면 어긋난다.
        val session = requireNotNull(withBisect(work) { service -> service.currentSession() })
        headCommit(work) shouldBe session.testing
    }

    test("동시 판정은 서로의 세션과 HEAD 를 덮어쓰지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }

        val outcomes = withSharedAccess(work) { access ->
            val first = BisectService(BisectGatewayImpl(access))
            val second = BisectService(BisectGatewayImpl(access))
            coroutineScope {
                val left = async(Dispatchers.Default) { runCatching { first.mark(BisectVerdict.BAD) } }
                val right = async(Dispatchers.Default) { runCatching { second.mark(BisectVerdict.GOOD) } }
                listOf(left.await(), right.await())
            }
        }

        outcomes.count { outcome -> outcome.isSuccess } shouldBeGreaterThanOrEqual 1
        outcomes.filter { outcome -> outcome.isFailure }.forEach { outcome ->
            outcome.exceptionOrNull().shouldBeInstanceOf<UndineException.StateViolation>()
        }
        // 세션의 검사 대상과 HEAD 가 일치한다 — 한 판정이 다른 판정의 HEAD 위에 얹혔다면 어긋난다.
        val session = requireNotNull(withBisect(work) { service -> service.currentSession() })
        headCommit(work) shouldBe session.testing
    }

    test("같은 세션을 읽은 두 판정 중 나중 것은 앞선 판정을 덮어쓰지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }
        val read = requireNotNull(withBisect(work) { service -> service.currentSession() })

        withGateway(work) { gateway ->
            gateway.saveSession(expected = read, session = read.marked(BisectVerdict.BAD, commits[4]))
            // 같은 세션을 읽고 뒤늦게 도착한 판정이다. 덮어쓰면 앞선 판정이 소리 없이 사라진다.
            shouldThrow<UndineException.StateViolation> {
                gateway.saveSession(expected = read, session = read.marked(BisectVerdict.GOOD, commits[4]))
            }.detail shouldBe SESSION_CHANGED
        }

        withBisect(work) { service -> service.currentSession() }?.bad shouldBe commits[4]
    }

    test("뒤늦게 도착한 검사 대상 체크아웃은 HEAD 를 옮기지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }
        val stale = requireNotNull(withBisect(work) { service -> service.currentSession() })
        withBisect(work) { service -> service.mark(BisectVerdict.BAD) }
        val afterMark = headCommit(work)

        withGateway(work) { gateway ->
            shouldThrow<UndineException.StateViolation> { gateway.beginProbe(stale, commits[6]) }
        }

        headCommit(work) shouldBe afterMark
    }

    test("시작 기록이 도중에 실패해도 시작 지점이 남아 새 Gateway 에서 reset 할 수 있다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        blockStateFileWrite(work, BISECT_LOG)

        withBisect(work) { service ->
            shouldThrow<UndineException.GitOperationFailed> {
                service.start(good = commits[0], bad = commits[7])
            }
        }

        // 진행 표식을 남기지 못했어도 되돌릴 자리는 먼저 확정돼 있어야 한다.
        startPointFile(work).exists() shouldBe true
        headBranch(work) shouldBe Constants.R_HEADS + MAIN

        withBisect(work) { service -> service.reset() }

        headBranch(work) shouldBe Constants.R_HEADS + MAIN
        startPointFile(work).exists() shouldBe false
        bisectLog(work).exists() shouldBe false
        bisectRefNames(work) shouldContainExactly emptyList()
    }

    test("첫 검사 대상 체크아웃 뒤 기록이 실패해도 HEAD 와 세션이 어긋나지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        val session = sessionOf(good = commits[0], bad = commits[7])

        withGateway(work) { gateway ->
            gateway.saveSession(expected = null, session = session)
            // 체크아웃은 그대로 두고 기록만 막는다.
            blockStateFileWrite(work, BISECT_LOG)
            shouldThrow<UndineException.GitOperationFailed> { gateway.beginProbe(session, commits[4]) }
        }

        // 검사 대상이 HEAD 이고 세션도 같은 커밋을 가리킨다 — 사용자가 엉뚱한 커밋에 판정을 붙이지 않는다.
        headCommit(work) shouldBe commits[4]
        withBisect(work) { service -> service.currentSession() }?.testing shouldBe commits[4]

        withBisect(work) { service -> service.reset() }
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
        bisectLog(work).exists() shouldBe false
    }

    test("판정을 저장한 뒤 다음 검사 대상 기록이 실패해도 판정과 시작 지점이 남는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }

        withGateway(work) { gateway ->
            val read = sessionOf(good = commits[0], bad = commits[7], testing = commits[4])
            val marked = read.marked(BisectVerdict.BAD, commits[4])
            gateway.saveSession(expected = read, session = marked)
            blockStateFileWrite(work, BISECT_LOG)
            shouldThrow<UndineException.GitOperationFailed> { gateway.beginProbe(marked, commits[2]) }
        }

        val restored = requireNotNull(withBisect(work) { service -> service.currentSession() })
        // 판정(bad 가 c5 로 좁혀진 것)이 살아 있고, 다음 대상과 HEAD 도 어긋나지 않는다.
        restored.bad shouldBe commits[4]
        restored.testing shouldBe commits[2]
        headCommit(work) shouldBe commits[2]
        startPointFile(work).exists() shouldBe true

        withBisect(work) { service -> service.reset() }
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
    }

    test("판정 전이는 복구 anchor 를 다시 쓰지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }
        val anchor = startPointFile(work)
        val recorded = anchor.readText()
        // anchor 쓰기가 실패하는 상황을 그대로 재현한다. 전이마다 다시 쓰면 여기서 세션이 깨진다.
        anchor.setWritable(false) shouldBe true

        val result = withBisect(work) { service -> service.mark(BisectVerdict.BAD) }

        result.shouldBeInstanceOf<BisectResult.Testing>()
        anchor.readText() shouldBe recorded
        // 판정과 다음 검사 대상은 정상적으로 진행됐다 — anchor 를 건드리지 않고도 전이가 끝난다.
        val session = requireNotNull(withBisect(work) { service -> service.currentSession() })
        session.bad shouldBe commits[4]
        headCommit(work) shouldBe session.testing

        anchor.setWritable(true) shouldBe true
        withBisect(work) { service -> service.reset() }
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
    }

    test("anchor 를 쓸 수 없으면 새 세션의 진행 상태를 만들지 않는다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        blockStateFileWrite(work, BISECT_START)

        withBisect(work) { service ->
            shouldThrow<UndineException.GitOperationFailed> {
                service.start(good = commits[0], bad = commits[7])
            }
        }

        // 돌아갈 자리를 확정하지 못했으면 아무것도 시작하지 않은 것이어야 한다.
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
        bisectLog(work).exists() shouldBe false
        bisectRefNames(work) shouldContainExactly emptyList()
    }

    test("충돌한 병합이 진행 중이면 시작하지 않고 병합 자리를 그대로 둔다") {
        val work = tempdir()
        val commits = seedDivergentBranches(work)
        Git.open(work).use { git -> git.merge().include(git.repository.findRef(SIDE)).call() }

        expectStartRejected(work, commits, RepositoryState.MERGING, MERGE_HEAD)
    }

    test("멈춘 리베이스가 진행 중이면 시작하지 않고 리베이스 자리를 그대로 둔다") {
        val work = tempdir()
        val commits = seedDivergentBranches(work)
        Git.open(work).use { git -> git.rebase().setUpstream(SIDE).call() }

        expectStartRejected(work, commits, RepositoryState.REBASING, REBASE_MERGE_DIR)
    }

    test("충돌한 cherry-pick 이 진행 중이면 시작하지 않고 cherry-pick 자리를 그대로 둔다") {
        val work = tempdir()
        val commits = seedDivergentBranches(work)
        Git.open(work).use { git -> git.cherryPick().include(git.repository.findRef(SIDE)).call() }

        expectStartRejected(work, commits, RepositoryState.CHERRY_PICKING, CHERRY_PICK_HEAD)
    }

    test("충돌한 revert 가 진행 중이면 시작하지 않고 revert 자리를 그대로 둔다") {
        val work = tempdir()
        val commits = seedDivergentBranches(work)
        Git.open(work).use { git ->
            // 뒤 커밋이 같은 줄을 다시 고쳤으므로 되돌리기가 충돌한다.
            git.revert().include(git.repository.resolve(commits[1].value)).call()
        }

        expectStartRejected(work, commits, RepositoryState.REVERTING, REVERT_HEAD)
    }

    test("이미 기록된 시작 지점과 다른 지점으로는 세션을 기록하지 못한다") {
        val work = tempdir()
        val commits = seedLinearHistory(work, count = 8)
        withBisect(work) { service -> service.start(good = commits[0], bad = commits[7]) }
        val read = requireNotNull(withBisect(work) { service -> service.currentSession() })

        withGateway(work) { gateway ->
            // 되돌릴 자리를 세션 도중에 옮기는 기록이다. 받아들이면 reset 이 있던 자리가 아닌 곳으로 간다.
            shouldThrow<UndineException.StateViolation> {
                gateway.saveSession(
                    expected = read,
                    session = read.copy(startPoint = BisectStartPoint.Detached(commits[2])),
                )
            }.detail shouldBe START_POINT_CHANGED
        }

        startPointFile(work).readText().trim() shouldBe MAIN
        withBisect(work) { service -> service.reset() }
        headBranch(work) shouldBe Constants.R_HEADS + MAIN
    }

    test("시작 지점을 읽은 뒤 HEAD 가 움직이면 옛 자리를 anchor 로 굳히지 않는다") {
        val work = tempdir()
        val commits = seedDivergentBranches(work)

        withGateway(work) { gateway ->
            val startPoint = gateway.startPoint()
            // 조회와 기록 사이에 사용자가 다른 브랜치로 옮겼다 — 옛 자리를 굳히면 reset 이 최신 위치가
            // 아니라 그 옛 자리로 되돌린다.
            Git.open(work).use { git -> git.checkout().setName(SIDE).call() }

            shouldThrow<UndineException.StateViolation> {
                gateway.saveSession(
                    expected = null,
                    session = BisectSession(
                        startPoint = startPoint,
                        good = listOf(commits[0]),
                        bad = commits[4],
                        skipped = emptyList(),
                        testing = null,
                    ),
                )
            }.detail shouldBe START_POINT_MOVED
        }

        // 거절은 아무것도 쓰기 전이다 — 반쪽 상태가 남으면 다음 시작이 "이미 진행 중" 으로 막힌다.
        startPointFile(work).exists() shouldBe false
        bisectLog(work).exists() shouldBe false
        bisectRefNames(work) shouldContainExactly emptyList()
        headBranch(work) shouldBe Constants.R_HEADS + SIDE
    }
})

/**
 * main 의 선형 이력과, 같은 파일을 다르게 고쳐 갈라진 곁가지.
 *
 * 병합·리베이스·cherry-pick·revert 를 **실제로 충돌시켜** 진행 중 상태를 만드는 씨앗이다.
 */
private fun seedDivergentBranches(work: File): List<CommitId> = initRepository(work).use { git ->
    val commits = (1..5).map { index -> CommitId.of(git.commitFile(CODE, "line $index\n").name) }
    // 곁가지는 중간에서 갈라진다 — main 끝에서 따면 fast-forward 라 충돌하지 않는다.
    git.checkout().setCreateBranch(true).setName(SIDE).setStartPoint(commits[2].value).call()
    git.commitFile(CODE, "side edit\n")
    git.checkout().setName(MAIN).call()
    commits
}

/**
 * 진행 중인 다른 연산 위에서 시작을 요청하면 거절되고, 그 연산의 자리가 그대로 남는지 본다.
 *
 * bisect 상태를 하나라도 남기면 [BISECT_LOG] 가 진행 상태를 가려 (상태 판정이 bisect 를 먼저 본다)
 * 화면이 continue/abort 를 안내하지 못하고, 검사 대상 체크아웃이 충돌 해결 중인 워킹트리를 덮는다.
 */
private suspend fun expectStartRejected(
    work: File,
    commits: List<CommitId>,
    state: RepositoryState,
    marker: String,
) {
    val headBefore = headCommit(work)
    val branchBefore = headBranch(work)

    withBisect(work) { service ->
        shouldThrow<UndineException.StateViolation> {
            service.start(good = commits.first(), bad = commits.last())
        }.detail shouldBe OTHER_OPERATION_IN_PROGRESS
    }

    // 진행 중이던 연산의 HEAD·상태 파일이 그대로여야 continue/abort 로 돌아갈 수 있다.
    headCommit(work) shouldBe headBefore
    headBranch(work) shouldBe branchBefore
    File(work, ".git/$marker").exists() shouldBe true
    Git.open(work).use { git -> git.repository.toOpenedRepository().state shouldBe state }
    // 새 세션은 흔적을 남기지 않는다 — 반쪽 bisect 상태가 남으면 다음 시작까지 막힌다.
    bisectLog(work).exists() shouldBe false
    startPointFile(work).exists() shouldBe false
    bisectRefNames(work) shouldContainExactly emptyList()
}

/** 오래된 것부터 [count] 건이 쌓인 선형 이력. */
private fun seedLinearHistory(work: File, count: Int): List<CommitId> =
    initRepository(work).use { git ->
        (1..count).map { index -> CommitId.of(git.commitFile(CODE, "line $index\n").name) }
    }

private data class MergedHistory(val base: CommitId, val mergeCommit: CommitId, val head: CommitId)

/**
 * 곁가지를 병합해 부모가 둘인 커밋이 후보 구간에 놓인 이력.
 *
 * 1차 구현이 계산하지 않는 형태를 **실제 저장소로** 재현한다 — 이 경계에서 후보를 임의로 고르면
 * 사용자가 틀린 커밋을 지목받는다.
 */
private fun seedMergedHistory(work: File): MergedHistory = initRepository(work).use { git ->
    val base = git.commitFile(CODE, "base\n")
    git.checkout().setCreateBranch(true).setName(SIDE).call()
    git.commitFile(OTHER, "side\n")
    git.checkout().setName(MAIN).call()
    git.commitFile(CODE, "main\n")
    val merge = git.merge()
        .include(git.repository.findRef(SIDE))
        .setCommit(true)
        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
        .setMessage("merge side")
        .call()
        .newHead
    val head = git.commitFile(CODE, "after merge\n")
    MergedHistory(
        base = CommitId.of(base.name),
        mergeCommit = CommitId.of(merge.name),
        head = CommitId.of(head.name),
    )
}

/** 공통 조상이 없는 두 커밋. 좁힐 구간 자체를 정의할 수 없는 경계다. */
private fun seedUnrelatedBranches(work: File): Pair<CommitId, CommitId> =
    initRepository(work).use { git ->
        val first = CommitId.of(git.commitFile(CODE, "first\n").name)
        // 고아 브랜치의 첫 커밋은 부모가 없어 어느 쪽도 상대의 조상이 아니다.
        git.checkout().setOrphan(true).setName(SIDE).call()
        val orphan = CommitId.of(git.commitFile(OTHER, "orphan\n").name)
        first to orphan
    }

private fun initRepository(work: File): Git =
    Git.init().setDirectory(work).setInitialBranch(MAIN).call()

private fun Git.commitFile(name: String, content: String): RevCommit {
    File(repository.workTree, name).writeText(content)
    add().addFilepattern(name).call()
    return commit().setMessage("commit $name $content").setAuthor(AUTHOR).setCommitter(AUTHOR).call()
}

private fun headCommit(work: File): CommitId = Git.open(work).use { git ->
    CommitId.of(git.repository.resolve(Constants.HEAD).name)
}

/** 붙어 있는 브랜치의 완전한 이름. detached 면 null 이다. */
private fun headBranch(work: File): String? = Git.open(work).use { git ->
    git.repository.exactRef(Constants.HEAD)?.takeIf { it.isSymbolic }?.target?.name
}

private fun bisectLog(work: File): File = File(work, ".git/$BISECT_LOG")

private fun startPointFile(work: File): File = File(work, ".git/$BISECT_START")

/** 시작 브랜치 위에서 만든 세션. 기록 실패 경로를 Gateway 계약 그대로 재현할 때 쓴다. */
private fun sessionOf(good: CommitId, bad: CommitId, testing: CommitId? = null): BisectSession =
    BisectSession(
        startPoint = BisectStartPoint.Branch(RefName(Constants.R_HEADS + MAIN)),
        good = listOf(good),
        bad = bad,
        skipped = emptyList(),
        testing = testing,
    )

/**
 * 상태 파일 자리에 **빈 디렉토리**를 놓아 쓰기만 막는다.
 *
 * 쓰기는 실패하지만 삭제는 가능하다 — 그래야 "기록에 실패한 뒤 reset 으로 빠져나온다" 를 볼 수 있다.
 */
private fun blockStateFileWrite(work: File, name: String) {
    val target = File(work, ".git/$name")
    target.delete()
    target.mkdirs()
}

private fun bisectRefNames(work: File): List<String> = Git.open(work).use { git ->
    git.repository.refDatabase.getRefsByPrefix(BISECT_REF_PREFIX).map { it.name }.sorted()
}

/**
 * `.git/` 바로 아래 남은 bisect 상태의 이름. 상태 파일이든 루트 참조든 여기 남으면 git 은 이 저장소를
 * bisect 중으로 본다 — 이름을 하나하나 헤아리지 않고 **남은 것 전부**를 본다.
 */
private fun leftoverBisectState(work: File): List<String> =
    File(work, ".git").list().orEmpty().filter { name -> name.startsWith("BISECT") }.sorted()

/** `refs/` 밖의 참조. 외부 CLI 의 `update-ref --no-deref` 와 같은 자리에 남긴다. */
private fun writeRootRef(work: File, name: String, commit: CommitId) = Git.open(work).use { git ->
    git.repository.updateRef(name, true).apply {
        setNewObjectId(ObjectId.fromString(commit.value))
        setForceUpdate(true)
    }.forceUpdate()
}

/** `refs/bisect/` 참조를 packed-refs 로 밀어 넣는다 — 느슨한 파일만 지우는 구현을 걸러낸다. */
private fun packRefs(work: File) = Git.open(work).use { git -> git.gc().call() }

/** 상태 파일은 남기고 참조만 걷어낸다 — 기록 도중 실패했거나 외부 도구가 지운 반쪽 상태다. */
private fun removeBisectRefs(work: File) = Git.open(work).use { git ->
    git.repository.refDatabase.getRefsByPrefix(BISECT_REF_PREFIX).forEach { ref ->
        git.repository.updateRef(ref.name).apply { setForceUpdate(true) }.delete()
    }
}

/**
 * 저장소를 연 [GitAccess] 로 Gateway·Service 를 새로 만들어 [block] 을 수행하고 반드시 닫는다.
 *
 * 호출마다 인스턴스를 새로 만드는 것이 곧 **앱을 다시 켠 상황**이다 — 세션이 메모리가 아니라
 * 저장소에서 복원된다는 것을 이 구조가 보장한다.
 */
private suspend fun <T> withBisect(work: File, block: suspend (BisectService) -> T): T =
    withGateway(work) { gateway -> block(BisectService(gateway)) }

private suspend fun <T> withGateway(work: File, block: suspend (BisectGatewayImpl) -> T): T =
    withSharedAccess(work) { gitAccess -> block(BisectGatewayImpl(gitAccess)) }

/**
 * 저장소를 연 [GitAccess] 하나를 [block] 에 넘긴다.
 *
 * 여러 Gateway 가 **같은 핸들을 공유**하는 상황이 곧 동시 호출이다 — `withRepository` 는 호출 단위로만
 * 직렬화하므로, 한 논리 전이가 나뉜 사이에 다른 호출이 끼어들 수 있다.
 */
private suspend fun <T> withSharedAccess(work: File, block: suspend (GitAccess) -> T): T {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(work.absolutePath)) { }
    return try {
        block(gitAccess)
    } finally {
        gitAccess.close()
    }
}
