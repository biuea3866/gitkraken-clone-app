package dev.undine.infrastructure.git.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectGateway
import dev.undine.domain.bisect.BisectSession
import dev.undine.domain.bisect.BisectStartPoint
import dev.undine.domain.bisect.BisectUnsupportedReason
import dev.undine.domain.bisect.CandidateRange
import dev.undine.domain.bisect.CandidateSurvey
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException

private const val OPERATION_SESSION = "bisect.currentSession"
private const val OPERATION_START_POINT = "bisect.startPoint"
private const val OPERATION_SURVEY = "bisect.surveyCandidates"
private const val OPERATION_SAVE = "bisect.saveSession"
private const val OPERATION_CHECKOUT = "bisect.checkout"
private const val OPERATION_CLEAR = "bisect.clearSession"

/**
 * [BisectGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 스레드 전환을 그 경계가 책임진다. 직접 여는 자원([Git]·[RevWalk])만
 * `use {}` 로 닫는다.
 *
 * 세션 상태는 [BisectStateFiles] 가 저장소의 `.git/` 표준 위치에 두므로 앱을 껐다 켜도 복원된다.
 * JGit 예외는 여기서 [UndineException] 으로 번역해 화면까지 그대로 올라가지 않게 한다.
 */
class BisectGatewayImpl(private val gitAccess: GitAccess) : BisectGateway {

    override suspend fun currentSession(): BisectSession? =
        gitOperation(OPERATION_SESSION) { repository -> repository.readBisectSession() }

    override suspend fun startPoint(): BisectStartPoint =
        gitOperation(OPERATION_START_POINT) { repository -> repository.currentStartPoint() }

    override suspend fun surveyCandidates(good: List<CommitId>, bad: CommitId): CandidateSurvey =
        gitOperation(OPERATION_SURVEY) { repository -> repository.survey(good, bad) }

    /**
     * 대조와 기록을 **한 임계구역**에서 끝낸다 — 나누면 대조한 뒤 쓰기 전에 다른 호출이 끼어들어
     * 대조가 무의미해진다.
     */
    override suspend fun saveSession(expected: BisectSession?, session: BisectSession) =
        gitOperation(OPERATION_SAVE) { repository ->
            repository.requireSessionUnchanged(expected)
            repository.writeBisectSession(session)
        }

    /**
     * 대조·체크아웃·세션 기록을 **한 임계구역**에서 끝낸다 — 두 번 들어가면 그 사이에 HEAD 만 움직인
     * 상태가 남는다. 체크아웃이 먼저라 실패하면 기록이 그대로여서 다시 시도할 수 있다.
     */
    override suspend fun beginProbe(expected: BisectSession, probe: CommitId) =
        gitOperation(OPERATION_CHECKOUT) { repository ->
            repository.requireSessionUnchanged(expected)
            repository.requireCommitId(probe)
            Git.wrap(repository).use { git -> git.checkout().setName(probe.value).call() }
            repository.writeBisectSession(expected.copy(testing = probe))
        }

    override suspend fun clearSession() =
        gitOperation(OPERATION_CLEAR) { repository ->
            val startPoint = repository.readStartPoint()
                ?: throw UndineException.StateViolation(NOT_BISECTING)
            // 되돌린 **뒤** 지운다 — 먼저 지우면 복구에 실패했을 때 돌아갈 자리를 잃는다.
            Git.wrap(repository).use { git -> git.checkout().setName(startPoint.checkoutName()).call() }
            repository.deleteBisectState()
        }

    private suspend fun <T> gitOperation(operation: String, block: (Repository) -> T): T =
        try {
            gitAccess.withRepository(block)
        } catch (failure: GitAPIException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: JGitInternalException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: IOException) {
            throw UndineException.GitOperationFailed(operation, failure)
        }
}

/** 되돌릴 때 체크아웃할 이름. 브랜치면 HEAD 가 다시 붙고, detached 였으면 그 커밋으로 돌아간다. */
private fun BisectStartPoint.checkoutName(): String = when (this) {
    is BisectStartPoint.Branch -> name.value
    is BisectStartPoint.Detached -> commit.value
}

/**
 * [good] 들과 [bad] 사이의 후보를 훑는다.
 *
 * 1차 구현은 **선형 이력만** 계산한다 — git 의 조상 집합 기준 중앙값을 재현하지 않으므로, 경로가
 * 갈리는 구간에서 후보를 고르면 근거 없는 선택이 된다. 못 하는 경우는 빈 결과가 아니라
 * [CandidateSurvey.NotLinear] 로 알린다.
 */
private fun Repository.survey(good: List<CommitId>, bad: CommitId): CandidateSurvey {
    val badId = requireCommitId(bad)
    val goodIds = good.map { commit -> requireCommitId(commit) }
    return surveyAncestry(badId, goodIds, bad) ?: enumerateRange(badId, goodIds)
}

/**
 * 좁힐 구간이 성립하는지 본다. 성립하면 null 이고, 아니면 그 사유가 결과다.
 *
 * 조상 관계 판정은 walk 상태를 남기므로 후보 열거와 walk 를 나눈다 (JGit `isMergedInto` 계약).
 */
private fun Repository.surveyAncestry(
    badId: ObjectId,
    goodIds: List<ObjectId>,
    bad: CommitId,
): CandidateSurvey? = RevWalk(this).use { walk ->
    val badCommit = walk.parseCommit(badId)
    val goodCommits = goodIds.map { id -> walk.parseCommit(id) }
    when {
        goodCommits.any { good -> walk.isMergedInto(badCommit, good) } ->
            CandidateSurvey.BadIsAncestorOfGood

        goodCommits.any { good -> !walk.isMergedInto(good, badCommit) } ->
            CandidateSurvey.NotLinear(BisectUnsupportedReason.GOOD_IS_NOT_ANCESTOR_OF_BAD, bad)

        else -> null
    }
}

/**
 * good 들을 제외하고 bad 에서 도달하는 커밋을 오래된 것부터 모은다.
 *
 * 병합 커밋이 하나라도 섞이면 경로가 갈린다는 뜻이라 후보를 고르지 않고 미지원으로 알린다.
 */
private fun Repository.enumerateRange(badId: ObjectId, goodIds: List<ObjectId>): CandidateSurvey {
    val newestFirst = RevWalk(this).use { walk ->
        // 커밋 시각이 같아도 부모가 자식보다 뒤에 오도록 위상 정렬을 강제한다.
        walk.sort(RevSort.TOPO)
        walk.markStart(walk.parseCommit(badId))
        goodIds.forEach { id -> walk.markUninteresting(walk.parseCommit(id)) }
        walk.toList()
    }
    val merge = newestFirst.firstOrNull { commit -> commit.parentCount > 1 }
    return if (merge != null) {
        CandidateSurvey.NotLinear(BisectUnsupportedReason.MERGE_COMMIT_IN_RANGE, CommitId.of(merge.name))
    } else {
        CandidateSurvey.Linear(
            CandidateRange(newestFirst.reversed().map { commit -> CommitId.of(commit.name) }),
        )
    }
}

/**
 * [commit] 을 저장소에서 찾는다.
 *
 * 없는 커밋을 조용히 건너뛰면 후보 구간이 말없이 넓어져 엉뚱한 커밋을 지목한다.
 *
 * @throws UndineException.NotFound 그 커밋이 없을 때
 */
private fun Repository.requireCommitId(commit: CommitId): ObjectId {
    val id = ObjectId.fromString(commit.value)
    // 부재는 예상되는 경우다 — 다른 저장소의 해시를 붙여넣는 일이 흔하다.
    if (!objectDatabase.has(id)) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
    }
    return id
}
