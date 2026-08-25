package dev.undine.infrastructure.git.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectStartPoint
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * 복구 anchor 만 따로 다룬다. 진행 상태(BisectStateFiles)와 **수명이 다르기** 때문이다 — 진행 상태는
 * 전이마다 갱신되지만 anchor 는 세션 시작 때 한 번 확정되고 reset 이 가장 마지막에 지운다.
 */

/** 시작 지점. git 은 브랜치 짧은 이름 또는 detached 시작 커밋의 해시를 넣는다. */
internal const val BISECT_START = "BISECT_START"

/**
 * [BISECT_START] 를 원자적으로 확정하기 위한 임시 자리. git 표준 파일이 아니라 이 앱 전용이므로
 * 표준 이름을 그대로 쓰지 않는다 — 외부 도구가 이것을 상태 파일로 읽으면 안 된다.
 */
private const val BISECT_START_STAGING = "BISECT_START.undine-new"

internal const val START_POINT_CHANGED =
    "이미 기록된 이분 탐색 시작 지점과 다른 지점으로는 기록할 수 없습니다"

internal const val START_POINT_MOVED =
    "시작 지점을 읽은 뒤 HEAD 가 움직였습니다 — 지금 위치에서 다시 시작하세요"

private const val NO_HEAD = "HEAD 를 읽을 수 없습니다"
private const val EMPTY_REPOSITORY = "커밋이 없는 저장소에서는 이분 탐색을 시작할 수 없습니다"

private val FULL_HASH = Regex("^[0-9a-f]{${Constants.OBJECT_ID_STRING_LENGTH}}$")

/**
 * 복구 anchor 를 **세션 시작 때 한 번만** 확정한다. 이후 전이는 값이 같은지만 보고 **다시 쓰지 않는다.**
 *
 * anchor 는 되돌릴 자리를 아는 유일한 근거다. 전이마다 다시 쓰면 그 쓰기가 실패할 때마다 anchor 를
 * 잃을 위험을 새로 만든다 — `BisectGatewayImpl.beginProbe` 처럼 HEAD 를 이미 옮긴 뒤 기록하는
 * 전이에서는 검사 대상에 붙은 채 돌아갈 자리를 잃는다. 세션 내내 값이 같으니 다시 쓸 이유도 없다.
 * anchor 와 진행 상태는 수명을 분리한다.
 *
 * 최초 기록은 임시 파일에 쓴 뒤 **원자적으로 옮긴다** — 반쯤 쓰인 anchor 가 남으면 엉뚱한 자리로
 * 되돌리거나, 값을 읽을 수 없는 채로 새 시작만 막는다.
 *
 * 최초 기록 직전에는 **같은 임계구역 안에서 HEAD 를 다시 읽어** [startPoint] 와 대조한다. 시작 지점
 * 조회와 기록은 저장소 접근이 나뉘므로, 그 사이 브랜치 체크아웃으로 HEAD 가 움직였다면 옛 자리가
 * anchor 로 굳어 reset 이 사용자의 최신 위치가 아닌 곳으로 되돌린다. 움직였으면 굳히지 않고 거절한다 —
 * 아무것도 쓰기 전이라 상태 파일도 참조도 남지 않는다.
 *
 * @throws UndineException.StateViolation 이미 기록된 시작 지점과 [startPoint] 가 다를 때, 또는 최초
 *   기록인데 그 사이 HEAD 가 [startPoint] 를 떠났을 때
 */
internal fun Repository.confirmStartPoint(startPoint: BisectStartPoint) {
    readStartPoint()?.let { recorded ->
        if (recorded != startPoint) throw UndineException.StateViolation(START_POINT_CHANGED)
        return
    }
    if (currentStartPoint() != startPoint) throw UndineException.StateViolation(START_POINT_MOVED)
    val staging = bisectFile(BISECT_START_STAGING)
    try {
        staging.writeText(startPoint.toFileContent() + "\n")
        Files.move(staging.toPath(), bisectFile(BISECT_START).toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        staging.delete()
    }
}

/**
 * 지금 HEAD 가 있는 자리. 브랜치 위면 브랜치, 아니면 커밋이다.
 *
 * @throws UndineException.StateViolation HEAD 를 읽을 수 없거나 커밋이 하나도 없을 때
 */
internal fun Repository.currentStartPoint(): BisectStartPoint {
    val head = exactRef(Constants.HEAD) ?: throw UndineException.StateViolation(NO_HEAD)
    if (head.isSymbolic) return BisectStartPoint.Branch(RefName(head.target.name))
    val commit = head.objectId ?: throw UndineException.StateViolation(EMPTY_REPOSITORY)
    return BisectStartPoint.Detached(CommitId.of(commit.name))
}

/** 되돌릴 지점. 세션을 통째로 읽지 않아도 reset 은 이 값만 있으면 된다. */
internal fun Repository.readStartPoint(): BisectStartPoint? {
    val raw = bisectFile(BISECT_START).takeIf { it.isFile }?.readText()?.trim()
    if (raw.isNullOrEmpty()) return null
    val branch = findRef(Constants.R_HEADS + raw)
    return when {
        // 짧은 이름을 먼저 브랜치로 해석한다 — 40자 hex 로 된 브랜치 이름도 만들 수 있기 때문이다.
        branch != null -> BisectStartPoint.Branch(RefName(branch.name))
        FULL_HASH.matches(raw) -> BisectStartPoint.Detached(CommitId.of(raw))
        // 시작 브랜치가 지워진 경우다. 커밋으로 추측하지 않고 그대로 둬 복구 실패로 드러나게 한다.
        else -> BisectStartPoint.Branch(RefName(Constants.R_HEADS + raw))
    }
}

private fun BisectStartPoint.toFileContent(): String = when (this) {
    is BisectStartPoint.Branch -> Repository.shortenRefName(name.value)
    is BisectStartPoint.Detached -> commit.value
}
