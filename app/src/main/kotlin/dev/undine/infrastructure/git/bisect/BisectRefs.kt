package dev.undine.infrastructure.git.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository

/** 참조 갱신·삭제가 성공으로 인정되는 결과. 그 밖은 상태 파일과 참조가 어긋나므로 실패로 올린다. */
private val ACCEPTED_REF_RESULTS = setOf(
    RefUpdate.Result.NEW,
    RefUpdate.Result.FORCED,
    RefUpdate.Result.NO_CHANGE,
    RefUpdate.Result.FAST_FORWARD,
)

internal fun Repository.bisectRefIds(prefix: String): List<CommitId> =
    refDatabase.getRefsByPrefix(prefix)
        .mapNotNull { ref -> ref.objectId }
        .map { id -> CommitId.of(id.name) }
        .sortedBy { id -> id.value }

internal fun Repository.updateBisectRef(name: String, commit: CommitId) {
    val update = updateRef(name)
    update.setNewObjectId(ObjectId.fromString(commit.value))
    update.setForceUpdate(true)
    update.setRefLogMessage(null, false)
    update.forceUpdate().requireSuccess(name)
}

internal fun Repository.deleteBisectRefs() = deleteStaleBisectRefs(keep = emptySet())

/**
 * `refs/` 밖에 있는 bisect 참조([name])를 지운다. 없으면 아무것도 하지 않는다.
 *
 * git 은 `BISECT_EXPECTED_REV`·`BISECT_HEAD` 를 파일이 아니라 참조로 다룬다
 * (`update-ref -d --no-deref`). 같은 이름의 파일만 지우면 참조를 다르게 저장하는 저장소에서 값이
 * 남아, 우리는 지웠다고 보고하는데 git 은 여전히 bisect 중으로 본다.
 *
 * 역참조하지 않는다 — 가리키는 커밋이나 브랜치가 아니라 **이 참조 자체**를 지우는 것이다.
 */
internal fun Repository.deleteBisectRootRef(name: String) {
    if (exactRef(name) == null) return
    val update = updateRef(name, true)
    update.setForceUpdate(true)
    update.setRefLogMessage(null, false)
    update.delete().requireSuccess(name)
}

/**
 * `refs/bisect/` 아래에서 [keep] 에 없는 참조만 지운다.
 *
 * 갱신은 덮어쓰기가 아니라 다시 쓰기다 — bad 가 좁혀지면 예전 good/skip 참조가 남으면 안 된다.
 * 다만 지금도 필요한 참조는 지우지 않는다. 지웠다 다시 만드는 사이는 세션을 읽을 수 없는 창이다.
 */
internal fun Repository.deleteStaleBisectRefs(keep: Set<String>) {
    refDatabase.getRefsByPrefix(BISECT_REF_PREFIX)
        .filterNot { ref -> ref.name in keep }
        .forEach { ref ->
            val update = updateRef(ref.name)
            update.setForceUpdate(true)
            update.setRefLogMessage(null, false)
            update.delete().requireSuccess(ref.name)
        }
}

/**
 * 참조 갱신 결과를 확인한다. 실패를 무시하면 상태 파일과 참조가 어긋난 세션이 남아 다음 걸음이
 * 엉뚱한 구간을 계산한다.
 */
private fun RefUpdate.Result.requireSuccess(ref: String) {
    if (this !in ACCEPTED_REF_RESULTS) {
        throw UndineException.StateViolation("bisect 참조 '$ref' 를 갱신하지 못했습니다: $this")
    }
}
