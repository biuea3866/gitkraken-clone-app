package dev.undine.infrastructure.git.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectSession
import dev.undine.infrastructure.git.repository.toOpenedRepository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.File

/** 사람이 읽는 진행 기록. **JGit 은 이 파일의 존재로 bisect 진행 중을 판정한다.** */
private const val BISECT_LOG = "BISECT_LOG"

/** 판정 용어. 이 앱은 git 기본값(bad/good)만 쓴다. */
private const val BISECT_TERMS = "BISECT_TERMS"

/**
 * 지금 판정을 기다리는 커밋. git 은 이것을 파일이 아니라 **참조**로 쓴다(`update-ref --no-deref`)
 * — 읽기·쓰기·삭제를 모두 Ref API 로 해야 참조 저장 방식이 다른 저장소에서도 어긋나지 않는다.
 */
private const val BISECT_EXPECTED_REV = "BISECT_EXPECTED_REV"

/**
 * `--no-checkout` 세션에서 검사 대상을 가리키는 참조. 이 앱은 항상 체크아웃하므로 쓰지 않지만,
 * 외부 CLI 가 남긴 것을 reset 이 지우지 못하면 git 은 이 저장소를 계속 bisect 중으로 본다.
 */
private const val BISECT_HEAD = "BISECT_HEAD"

/** git 이 남기지만 이 앱은 쓰지 않는 파일. reset 이 함께 지워야 상태가 깨끗해진다. */
private const val BISECT_ANCESTORS_OK = "BISECT_ANCESTORS_OK"
private const val BISECT_NAMES = "BISECT_NAMES"
private const val BISECT_FIRST_PARENT = "BISECT_FIRST_PARENT"
private const val BISECT_RUN = "BISECT_RUN"
private const val BISECT_RESET_WHEN_FOUND = "BISECT_RESET_WHEN_FOUND"

/**
 * [BISECT_START] 를 뺀 상태 **파일**. 기록의 **역순**으로 지우려고 진행 표식([BISECT_LOG])을 앞에 둔다.
 *
 * 여기 없는 두 부류가 있다.
 * - [BISECT_START] 는 따로 **가장 마지막**에 지운다 — 이 파일이 "되돌릴 자리를 안다" 의 근거라,
 *   중간에 실패해도 이것만 남아 있으면 reset 을 다시 시도할 수 있다.
 * - [BISECT_EXPECTED_REV]·[BISECT_HEAD] 는 파일이 아니라 참조다 ([BISECT_ROOT_REFS]).
 *
 * 이 앱이 쓰지 않는 이름까지 담는 이유는 **표준 집합 전체**를 지워야 하기 때문이다 — 같은 저장소를
 * 외부 git CLI 와 오간다는 것이 이 설계의 전제라, 우리가 쓴 것만 지우면 우리는 reset 했는데 git 은
 * 아니라고 한다.
 */
private val BISECT_FILES = listOf(
    BISECT_LOG,
    BISECT_TERMS,
    BISECT_ANCESTORS_OK,
    BISECT_NAMES,
    BISECT_FIRST_PARENT,
    BISECT_RUN,
    BISECT_RESET_WHEN_FOUND,
)

/** `refs/` 밖에 있는 표준 bisect 참조. 파일로 지우면 packed·reftable 저장소에서 남는다. */
private val BISECT_ROOT_REFS = listOf(BISECT_EXPECTED_REV, BISECT_HEAD)

internal const val BISECT_REF_PREFIX = "refs/bisect/"
private const val BAD_REF = BISECT_REF_PREFIX + "bad"
private const val GOOD_REF_PREFIX = BISECT_REF_PREFIX + "good-"
private const val SKIP_REF_PREFIX = BISECT_REF_PREFIX + "skip-"

private const val TERMS_CONTENT = "bad\ngood\n"

internal const val NO_BAD_COMMIT =
    "bisect 세션에 bad 커밋이 없습니다 — 외부 도구가 시작한 세션은 그 도구로 이어가세요"
internal const val NOT_BISECTING = "이분 탐색이 진행 중이 아닙니다"
internal const val ALREADY_BISECTING = "이분 탐색이 이미 진행 중입니다"
internal const val OTHER_OPERATION_IN_PROGRESS =
    "진행 중인 병합·리베이스·revert·cherry-pick 이 있어 이분 탐색을 시작하지 않았습니다"
internal const val SESSION_CHANGED =
    "다른 작업이 이분 탐색 상태를 먼저 바꿨습니다 — 현재 세션을 다시 읽고 시도하세요"
private const val NO_GIT_DIRECTORY = "저장소 디렉토리가 없어 bisect 상태를 다룰 수 없습니다"

/**
 * 새 세션을 얹으면 안 되는 상태. 끝내야 빠져나오는 다른 Git 연산이 진행 중인 경우다.
 *
 * **DETACHED·EMPTY 는 여기 없다** — detached HEAD 는 bisect 가 스스로 만드는 자리이고, 빈 저장소는
 * 대상 커밋을 못 찾아 다른 사유로 먼저 걸린다. 막아야 하는 것은 "이미 무언가 진행 중" 뿐이다
 * (`MergeGatewayImpl` 의 같은 판단과 맞춘다).
 */
private val CONFLICTING_STATES = setOf(
    RepositoryState.MERGING,
    RepositoryState.REBASING,
    RepositoryState.REVERTING,
    RepositoryState.CHERRY_PICKING,
)

/**
 * 저장소 안(`.git/` 하위)의 표준 bisect 상태를 읽고 쓴다.
 *
 * **앱 설정에 복제하지 않는다.** 같은 저장소를 외부 git CLI 로 오갔을 때 두 벌의 상태가 어긋나면
 * 사용자가 어느 쪽을 믿어야 할지 알 수 없다. 파일·참조 이름을 git 표준 그대로 쓰는 이유도 같다.
 */
internal fun Repository.readBisectSession(): BisectSession? {
    val startPoint = readStartPoint() ?: return null
    // 시작 파일은 있는데 bad 가 없으면 이 앱이 이어갈 수 없는 세션이다. 빈 결과로 감추면 화면이
    // "진행 중이 아님" 으로 오해하고, 사용자는 왜 시작할 수 없는지 알 수 없다.
    val bad = exactRef(BAD_REF)?.objectId ?: throw UndineException.StateViolation(NO_BAD_COMMIT)
    return BisectSession(
        startPoint = startPoint,
        good = bisectRefIds(GOOD_REF_PREFIX),
        bad = CommitId.of(bad.name),
        skipped = bisectRefIds(SKIP_REF_PREFIX),
        testing = exactRef(BISECT_EXPECTED_REV)?.objectId?.let { id -> CommitId.of(id.name) },
    )
}

/**
 * 세션을 기록한다. **되돌릴 수 있는 순서**로 쓴다 — 시작 지점을 먼저 확정하고, 진행 표식을 마지막에
 * 공개한다.
 *
 * 순서가 안전장치다.
 * - 복구 anchor([BISECT_START])가 **가장 먼저**다. 뒤이은 어느 쓰기가 실패해도 reset 이 시작
 *   지점으로 빠져나갈 수 있다. 마지막에 쓰면 그 한 번의 실패로 진행 상태만 남고 돌아갈 자리를
 *   잃는다. 다만 anchor 는 **세션 시작 때 한 번만** 쓴다 — [confirmStartPoint] 참조.
 * - 그다음이 경계 참조다. 참조가 갖춰진 뒤에 진행 표식을 공개하면 실패는 "아직 이어갈 수 없는
 *   세션"(reset 은 가능)으로 떨어지지, 없는 진행을 지어내지 않는다.
 * - [BISECT_LOG] 가 **가장 마지막**이다. JGit 이 이 파일로 bisect 진행 중을 판정하므로, 상태가 다
 *   갖춰지기 전에 공개하면 반쪽 상태가 진행 중으로 보인다.
 *
 * bad 참조는 지웠다 다시 만들지 않고 **제자리에서 갱신**한다 — 지우는 순간이 곧 세션을 읽을 수 없는
 * 창이다. 지금 세션에 없는 good/skip 참조만 걷어낸다.
 */
internal fun Repository.writeBisectSession(session: BisectSession) {
    val boundaryRefs = buildMap {
        session.good.forEach { good -> put(GOOD_REF_PREFIX + good.value, good) }
        session.skipped.forEach { skipped -> put(SKIP_REF_PREFIX + skipped.value, skipped) }
    }

    confirmStartPoint(session.startPoint)

    updateBisectRef(BAD_REF, session.bad)
    deleteStaleBisectRefs(keep = boundaryRefs.keys + BAD_REF)
    boundaryRefs.forEach { (name, commit) -> updateBisectRef(name, commit) }

    bisectFile(BISECT_TERMS).writeText(TERMS_CONTENT)
    session.testing?.let { testing -> updateBisectRef(BISECT_EXPECTED_REV, testing) }
        ?: deleteBisectRootRef(BISECT_EXPECTED_REV)

    bisectFile(BISECT_LOG).writeText(session.toLogContent())
}

/**
 * 저장소의 현재 세션이 [expected] 그대로인지 확인하고, 아니면 거절한다.
 *
 * 한 논리 전이(시작·판정)가 `GitAccess` 임계구역을 여러 번 나눠 들어가므로, 그 사이에 다른 호출이
 * 세션을 바꿨을 수 있다. 쓰기와 **같은 임계구역 안에서** 다시 대조해야 두 호출이 서로의 세션과
 * HEAD 를 덮어쓰지 않는다.
 *
 * 세션이 아직 없어야 하는 경우([expected] 가 null)는 [BISECT_START] 하나로 판정한다 — 참조까지
 * 읽으면 반쪽 상태에서 "이미 진행 중" 대신 다른 사유가 올라와 새 시작이 막힌 이유를 가린다.
 * 시작은 **다른 연산이 진행 중이 아닌지도** 함께 본다 ([requireNoOtherOperationInProgress]).
 *
 * 시작 지점을 읽은 뒤 HEAD 가 움직였는지는 여기서 보지 않는다 — anchor 를 굳히는 자리인
 * [confirmStartPoint] 가 **같은 임계구역 안에서** HEAD 를 다시 읽어 대조한다.
 *
 * @throws UndineException.StateViolation 저장소의 세션이 [expected] 와 다를 때, 또는 시작인데 다른
 *   Git 연산이 진행 중일 때
 */
internal fun Repository.requireSessionUnchanged(expected: BisectSession?) {
    if (expected == null) {
        if (readStartPoint() != null) throw UndineException.StateViolation(ALREADY_BISECTING)
        requireNoOtherOperationInProgress()
        return
    }
    // good/skip 은 참조로 저장돼 [readBisectSession] 이 해시 순으로 돌려준다. 판정한 순서만 다른 같은
    // 세션을 "바뀐 세션" 으로 오인하지 않도록 [expected] 도 같은 기준으로 맞춰 비교한다.
    val sameOrder = expected.copy(
        good = expected.good.sortedBy { commit -> commit.value },
        skipped = expected.skipped.sortedBy { commit -> commit.value },
    )
    if (readBisectSession() != sameOrder) throw UndineException.StateViolation(SESSION_CHANGED)
}

/**
 * 병합·리베이스·revert·cherry-pick 이 진행 중이면 새 세션을 시작하지 않는다.
 *
 * 얹으면 두 가지가 깨진다.
 * - [BISECT_LOG] 가 진행 중이던 연산을 **가린다** — 상태 판정이 bisect 를 먼저 보므로
 *   (`OpenedRepositoryMapping`), 화면은 continue/abort 대신 bisect 를 안내한다.
 * - 첫 검사 대상 체크아웃이 충돌 해결 중인 워킹트리와 그 연산의 HEAD 를 옮긴다 — 돌아갈 자리를 잃는다.
 *
 * 판정은 기록과 **같은 임계구역** 안이다. 나누면 확인한 뒤 쓰기 전에 다른 연산이 시작될 수 있다.
 *
 * @throws UndineException.StateViolation 다른 Git 연산이 진행 중일 때
 */
private fun Repository.requireNoOtherOperationInProgress() {
    if (toOpenedRepository().state in CONFLICTING_STATES) {
        throw UndineException.StateViolation(OTHER_OPERATION_IN_PROGRESS)
    }
}

/**
 * **표준 bisect 상태 전부**를 지운다 — 상태 파일([BISECT_FILES]), `refs/bisect/` 아래 참조,
 * 루트 참조([BISECT_ROOT_REFS]), 그리고 복구 anchor([BISECT_START]). 되돌리기가 끝난 **뒤** 호출한다.
 *
 * 지우는 대상은 **이 앱이 쓴 것이 아니라 git 표준 집합**이다. 외부 CLI 가 `--no-checkout` 이나 `run`
 * 으로 남긴 것까지 걷어내야 reset 뒤에 git 이 이 저장소를 bisect 중으로 보지 않는다 (AC #8 의 전제).
 *
 * 기록의 **역순**이다 — 진행 표식([BISECT_LOG])을 먼저 내리고, 복구 anchor([BISECT_START])를 가장
 * 마지막에 지운다. 중간에 실패해도 anchor 가 남아 있어야 새 Gateway 인스턴스에서 reset 을 다시
 * 시도할 수 있다.
 *
 * 하나라도 지우지 못하면 실패로 올린다 — 남은 상태를 성공으로 보고하면 다음 시작이 "이미 진행 중"
 * 으로 막히고, 사용자는 reset 이 끝났다고 믿는다.
 */
internal fun Repository.deleteBisectState() {
    BISECT_FILES.forEach { name -> bisectFile(name).deleteOrFail() }
    deleteBisectRefs()
    BISECT_ROOT_REFS.forEach { name -> deleteBisectRootRef(name) }
    bisectFile(BISECT_START).deleteOrFail()
}

/**
 * 사람이 읽는 진행 기록. git 의 `BISECT_LOG` 형식을 따른다 — 외부 `git bisect log` 가 읽는 파일이라
 * 자체 형식을 발명하지 않는다.
 */
private fun BisectSession.toLogContent(): String = buildString {
    appendLine("git bisect start")
    appendLine("# bad: [${bad.value}]")
    appendLine("git bisect bad ${bad.value}")
    good.forEach { commit ->
        appendLine("# good: [${commit.value}]")
        appendLine("git bisect good ${commit.value}")
    }
    skipped.forEach { commit ->
        appendLine("# skip: [${commit.value}]")
        appendLine("git bisect skip ${commit.value}")
    }
}

internal fun Repository.bisectFile(name: String): File =
    File(directory ?: throw UndineException.StateViolation(NO_GIT_DIRECTORY), name)

/**
 * 있으면 지우고, 지우지 못했으면 실패로 올린다.
 *
 * `delete()` 의 false 를 무시하면 남아 있는 상태 파일을 지웠다고 보고하게 된다.
 */
private fun File.deleteOrFail() {
    if (!exists()) return
    if (!delete()) {
        throw UndineException.StateViolation("bisect 상태 파일 '$name' 을 지우지 못했습니다")
    }
}
