package dev.undine.domain.reflog

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import java.time.Instant

/**
 * reflog 항목 하나 — 참조가 언제 어디서 어디로 움직였는가.
 *
 * @param from 이 움직임 **전에** 가리켰던 커밋. 저장소 최초 항목은 0 해시라서 null 이다.
 * @param action git 이 남긴 동작 설명(`commit`·`reset: moving to …`·`rebase (finish)` 등). 화면이
 *   "무엇 때문에 움직였는지" 를 보여주는 유일한 단서라 가공하지 않고 그대로 전한다.
 */
data class ReflogEntry(
    val index: Int,
    val from: CommitId?,
    val to: CommitId,
    val action: String,
    val who: Person,
    val at: Instant,
)

/**
 * reflog 조회 결과.
 *
 * [mayBeExpired] 는 "기록이 없다" 와 "조회에 실패했다" 를 구분하려고 담는다 — reflog 는 기본 90일 뒤
 * 만료되므로, 비어 있음이 곧 "그런 일이 없었다" 는 뜻이 아니다.
 */
data class ReflogPage(val entries: List<ReflogEntry>, val mayBeExpired: Boolean)

/**
 * 복구 방식.
 *
 * **기본은 새 브랜치 생성**이다. 기존 ref 를 옮기면 그 ref 가 가리키던 커밋을 또 잃을 수 있어,
 * 되찾으러 온 사용자가 새로 잃는 일이 생긴다 — 그래서 이동은 명시적 선택으로만 받는다.
 */
sealed interface RecoveryTarget {

    /** [name] 브랜치를 새로 만들어 그 지점을 가리키게 한다. */
    data class NewBranch(val name: RefName) : RecoveryTarget

    /**
     * 이미 있는 [name] 을 그 지점으로 옮긴다. **그 ref 가 가리키던 커밋을 잃을 수 있다.**
     *
     * @param confirmation 무엇이 밀려나는지 사용자에게 보여 주고 받은 확인.
     */
    data class MoveExisting(val name: RefName, val confirmation: RefMoveConfirmation) : RecoveryTarget
}

/**
 * 기존 ref 를 옮기는 것이 **그 ref 의 현재 커밋을 밀어낸다**는 사실을 사용자가 확인했다는 증거.
 *
 * Boolean 이 아니라 타입인 이유는 호출부가 확인 절차를 건너뛸 수 없게 하려는 것이다 — 확인 없는
 * 이동은 컴파일되지 않는다. [displacedCommit] 은 화면이 "이 커밋이 밀려납니다" 로 보여 준 값이다.
 */
class RefMoveConfirmation private constructor(val displacedCommit: CommitId) {

    /**
     * 실행 직전의 실제 밀려날 커밋 [displacedNow] 이 확인 시점 값과 같은지 재검증한다.
     *
     * 조회와 실행 사이에 ref 가 움직였다면 사용자는 **다른 커밋이 밀려난다는 것을 모르고** 확인한
     * 것이므로 옮기지 않고 재조회를 요구한다. `AmendConfirmation` 의 낡은 확인 거부와 같은 문제라
     * 같은 방식으로 다룬다.
     *
     * @throws UndineException.StateViolation 확인 값과 지금 밀려날 커밋이 다르거나, 옮길 ref 가
     *   가리키는 커밋이 없을 때
     */
    fun validateFor(displacedNow: CommitId?) {
        if (displacedNow != displacedCommit) {
            throw UndineException.StateViolation(
                "$STALE_CONFIRMATION (확인: ${displacedCommit.value}, 현재: ${displacedNow?.value ?: NO_COMMIT})",
            )
        }
    }

    companion object {
        private const val STALE_CONFIRMATION =
            "확인한 커밋과 지금 밀려날 커밋이 달라 옮기지 않았습니다. 대상을 다시 조회한 뒤 확인하세요"
        private const val NO_COMMIT = "없음"

        fun ofDisplacedCommit(displacedCommit: CommitId): RefMoveConfirmation =
            RefMoveConfirmation(displacedCommit)
    }
}

/**
 * 도달 불가 커밋 탐색 결과.
 *
 * 빈 목록으로 "없음" 과 "이 저장소에서는 탐색할 수 없음" 을 뭉개지 않는다 — 잃어버린 커밋을 찾으러
 * 온 사용자에게 조용한 fallback 은 "없다" 는 오답이 된다.
 */
sealed interface UnreachableCommitScan {

    /** 객체 DB 를 실제로 훑었다. [commits] 가 비면 정말로 도달 불가 커밋이 없다는 뜻이다. */
    data class Scanned(val commits: List<Commit>) : UnreachableCommitScan

    /** 이 저장소의 객체 저장 방식으로는 훑을 수 없다. 화면은 "없음" 이 아니라 미지원으로 알린다. */
    data class NotSupported(val reason: Reason) : UnreachableCommitScan {

        enum class Reason {
            /** 파일 기반 객체 DB 가 아니다 — 객체를 나열하는 공개 API 가 없다. */
            NON_FILE_OBJECT_DATABASE,
        }
    }
}

/**
 * reflog 조회와 유실 커밋 복구 계약. 구현은 `ReflogGatewayImpl` 이다.
 *
 * 잘못된 reset·rebase·브랜치 삭제로 잃어버린 커밋을 되찾는 경로다 — 터미널에서 reflog 를 읽는 것은
 * 진입 장벽이 높아, GUI 가 있어야 할 이유 중 하나다.
 */
interface ReflogGateway {

    /** HEAD 의 reflog. 최신 항목이 앞이다. */
    suspend fun headReflog(limit: Int): ReflogPage

    /**
     * [ref] 의 reflog. 최신 항목이 앞이다.
     *
     * 브랜치를 지우면 그 ref 의 기록도 함께 사라진다 — 삭제된 브랜치를 되찾는 단서는 [headReflog] 다.
     *
     * @throws UndineException.NotFound 그 ref 가 없을 때. 빈 결과로 뭉개면 화면이 "움직인 적 없는
     *   브랜치" 로 오해한다.
     */
    suspend fun refReflog(ref: RefName, limit: Int): ReflogPage

    /**
     * 어떤 참조에서도 닿지 않고 **reflog 에도 남지 않은** 커밋. reflog 로 되찾을 수 없을 때의 마지막
     * 수단이다.
     *
     * **느리다** — 객체 DB 전체를 훑으므로 reflog 조회와 별도 진입점으로 둔다.
     */
    suspend fun unreachableCommits(limit: Int): UnreachableCommitScan

    /**
     * [at] 지점을 [target] 방식으로 되살린다.
     *
     * @throws UndineException.NotFound [at] 커밋을 찾을 수 없을 때
     * @throws UndineException.StateViolation 새 브랜치 이름이 이미 있거나, 옮길 ref 가 없거나,
     *   확인한 커밋과 지금 밀려날 커밋이 다를 때
     */
    suspend fun recover(at: CommitId, target: RecoveryTarget): RefName
}
