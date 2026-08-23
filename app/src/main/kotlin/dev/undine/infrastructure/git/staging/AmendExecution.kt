package dev.undine.infrastructure.git.staging

import dev.undine.domain.AmendConfirmation
import dev.undine.domain.AmendPreflight
import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult
import dev.undine.domain.UndineException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

internal const val AMEND_BACKUP_REF_PREFIX = "refs/undine/amend-backup/"
private const val NO_AMEND_TARGET_DETAIL = "고칠 이전 커밋이 없습니다"
private const val AMEND_BACKUP_FAILED_DETAIL = "amend 대상을 백업하지 못해 커밋을 고치지 않았습니다"

/** 백업 ref 이름에 붙이는 커밋 약어 길이 — 같은 밀리초의 서로 다른 amend 를 구분한다. */
private const val BACKUP_ABBREVIATION_LENGTH = 8

/** 고칠 대상과 그 커밋의 원격 포함 여부를 읽는다. 저장소를 바꾸지 않는다. */
internal fun Repository.inspectAmendTarget(): AmendPreflight {
    val target = requireAmendTarget()
    return AmendPreflight(target = CommitId.of(target.name), existsOnRemote = existsOnRemote(target))
}

/**
 * HEAD 를 고쳐 쓴다. 순서는 **대상 확인 → 허가 재검사 → 백업 → 실행** 이다.
 *
 * 허가 재검사가 백업보다 앞인 이유는 거부된 amend 가 흔적(ref)을 남기지 않아야 하기 때문이고,
 * 백업이 실행보다 앞인 이유는 복구 지점 없이 HEAD 를 다시 쓰지 않기 위해서다.
 * 대상과 원격 포함 여부를 여기서 **다시 읽는다** — preflight 와 실행 사이에 저장소가 바뀔 수 있고,
 * UseCase 를 거치지 않은 호출도 같은 가드를 통과해야 한다.
 */
internal fun Repository.amendCommit(
    message: String,
    confirmation: AmendConfirmation,
    currentTimeMillis: () -> Long,
): CommitResult {
    val target = requireAmendTarget()
    confirmation.validateFor(CommitId.of(target.name), existsOnRemote(target))
    backupAmendTarget(target, currentTimeMillis())
    return Git(this).use { git ->
        CommitResult(commitId = CommitId.of(git.commit().setMessage(message).setAmend(true).call().name))
    }
}

private fun Repository.requireAmendTarget(): ObjectId =
    resolve(Constants.HEAD) ?: throw UndineException.StateViolation(NO_AMEND_TARGET_DETAIL)

/**
 * 고치기 전 커밋을 `refs/undine/amend-backup/<브랜치>-<epochMillis>-<커밋 약어>` 로 남긴다.
 * 백업에 실패하면 **amend 를 진행하지 않는다** — 복구 지점 없이 HEAD 를 다시 쓰지 않는다.
 *
 * 이름에 커밋 약어를 붙이고 **force update 를 쓰지 않는다.** 시각만으로 이름을 지으면 같은 밀리초에
 * 두 번 amend 할 때 앞선 복구 지점이 조용히 덮어써진다 — 덮어쓰기를 막으려는 백업이 스스로
 * 덮어쓰는 셈이다. 같은 커밋을 같은 밀리초에 두 번 백업하는 경우만 이름이 겹치고, 그때는 내용이
 * 같으므로 [RefUpdate.Result.NO_CHANGE] 로 통과한다.
 */
private fun Repository.backupAmendTarget(target: ObjectId, now: Long) {
    val branchName = branch ?: Constants.HEAD
    val abbreviation = target.abbreviate(BACKUP_ABBREVIATION_LENGTH).name()
    val update = updateRef("$AMEND_BACKUP_REF_PREFIX$branchName-$now-$abbreviation")
    update.setNewObjectId(target)
    update.setRefLogMessage("undine: amend backup", false)
    val result = update.update()
    val stored = result == RefUpdate.Result.NEW || result == RefUpdate.Result.NO_CHANGE
    if (!stored) throw UndineException.StateViolation("$AMEND_BACKUP_FAILED_DETAIL ($result)")
}

/**
 * amend 대상이 현재 브랜치 업스트림의 remote-tracking ref 에 포함되는지 본다.
 * 업스트림이 없으면 "모른다" 가 아니라 false 다 — 확인을 못 띄우는 쪽보다 안전하다.
 */
private fun Repository.existsOnRemote(target: ObjectId): Boolean {
    val trackingBranch = branch?.let { BranchConfig(config, it).trackingBranch }
    val trackingTip = trackingBranch?.let { resolve(it) } ?: return false
    return RevWalk(this).use { walk ->
        walk.isMergedInto(walk.parseCommit(target), walk.parseCommit(trackingTip))
    }
}
