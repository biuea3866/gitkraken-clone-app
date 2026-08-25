package dev.undine.domain.reflog

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val DISPLACED_HASH = "1111111111111111111111111111111111111111"
private const val OTHER_HASH = "2222222222222222222222222222222222222222"

private val DISPLACED = CommitId.of(DISPLACED_HASH)
private val OTHER = CommitId.of(OTHER_HASH)

/**
 * reflog 계약의 **표현 규칙**을 저장소 없이 검증한다 — 빈 결과와 만료 가능성의 구분, 복구 대상의
 * 기본값, 낡은 확인 거부. JGit 동작은 `ReflogGatewayImplSpec` 이 실제 저장소로 검증한다.
 */
class ReflogContractSpec : FunSpec({

    test("항목이 없는 페이지는 만료 가능성을 함께 담아 기록 부재와 조회 실패를 구분한다") {
        val page = ReflogPage(entries = emptyList(), mayBeExpired = true)

        page.entries.shouldBeEmpty()
        // 예외가 아니라 값으로 알린다 — 비어 있음이 곧 "그런 일이 없었다" 는 뜻이 아니다.
        page.mayBeExpired shouldBe true
    }

    test("복구 기본 대상은 새 브랜치 생성이라 확인 없이 만들 수 있다") {
        val target: RecoveryTarget = RecoveryTarget.NewBranch(RefName("rescue"))

        target.shouldBeInstanceOf<RecoveryTarget.NewBranch>().name shouldBe RefName("rescue")
    }

    test("기존 ref 이동은 확인 값을 가진 명시적 대상으로만 표현된다") {
        val confirmation = RefMoveConfirmation.ofDisplacedCommit(DISPLACED)

        val target = RecoveryTarget.MoveExisting(RefName("main"), confirmation)

        target.confirmation.displacedCommit shouldBe DISPLACED
    }

    test("확인한 커밋이 지금 밀려날 커밋과 같으면 이동을 허용한다") {
        shouldNotThrowAny {
            RefMoveConfirmation.ofDisplacedCommit(DISPLACED).validateFor(DISPLACED)
        }
    }

    test("조회 뒤 ref 가 움직였으면 낡은 확인으로 거부하고 재조회를 요구한다") {
        val failure = shouldThrow<UndineException.StateViolation> {
            RefMoveConfirmation.ofDisplacedCommit(DISPLACED).validateFor(OTHER)
        }

        failure.detail shouldBe
            "확인한 커밋과 지금 밀려날 커밋이 달라 옮기지 않았습니다. 대상을 다시 조회한 뒤 확인하세요 " +
            "(확인: $DISPLACED_HASH, 현재: $OTHER_HASH)"
    }

    test("밀려날 커밋이 사라졌으면 확인 없이 옮기지 않는다") {
        shouldThrow<UndineException.StateViolation> {
            RefMoveConfirmation.ofDisplacedCommit(DISPLACED).validateFor(null)
        }
    }

    test("탐색 결과는 훑은 빈 결과와 미지원을 서로 다른 타입으로 구분한다") {
        val scanned: UnreachableCommitScan = UnreachableCommitScan.Scanned(emptyList())
        val unsupported: UnreachableCommitScan = UnreachableCommitScan.NotSupported(
            UnreachableCommitScan.NotSupported.Reason.NON_FILE_OBJECT_DATABASE,
        )

        scanned.shouldBeInstanceOf<UnreachableCommitScan.Scanned>().commits.shouldBeEmpty()
        unsupported.shouldBeInstanceOf<UnreachableCommitScan.NotSupported>().reason shouldBe
            UnreachableCommitScan.NotSupported.Reason.NON_FILE_OBJECT_DATABASE
    }
})
