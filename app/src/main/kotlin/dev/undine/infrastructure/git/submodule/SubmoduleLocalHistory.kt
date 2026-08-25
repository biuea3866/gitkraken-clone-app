package dev.undine.infrastructure.git.submodule

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/**
 * **파일이 깨끗해도 커밋은 남을 수 있다.** 워킹트리와 `.git/modules` 를 함께 지우면 서브모듈 안에만
 * 있던 커밋에 닿을 길이 사라지므로, 부모가 기록한 [recorded] 커밋으로 되찾을 수 없는 HEAD·로컬 참조는
 * 파일 상태와 무관하게 보존 대상이다.
 *
 * "지우면 되살릴 수 없는 것" 은 파일에 한정되지 않는다 — divergent HEAD, 기록 커밋에서 도달할 수 없는
 * 모든 ref namespace와 stash 는 모두 저장소가 되찾아 줄 수 없다. namespace를 열거하면 사용자가 만든
 * ref 또는 remote 아래의 로컬 전용 ref가 빠져 다시 유실 경로가 된다.
 */
internal fun Repository.historyEntries(recorded: ObjectId?): List<PreservedEntry> =
    headEntries(recorded) + localRefEntries(recorded)

/**
 * HEAD 는 **기록 커밋과 같은지**로 본다. 조상이라 도달 가능하더라도 체크아웃된 자리가 다르면 부모
 * gitlink 만으로 그 자리를 되살릴 수 없다. HEAD 가 없으면(체크아웃 전) 갇힌 커밋도 없다.
 */
private fun Repository.headEntries(recorded: ObjectId?): List<PreservedEntry> =
    resolve(Constants.HEAD)
        ?.takeIf { head -> head != recorded }
        ?.let { listOf(PreservedEntry(Constants.HEAD, PreservationReason.DIVERGED)) }
        .orEmpty()

/**
 * 기준점이 없으면(부모 인덱스에 gitlink 가 없음) 무엇을 되찾을 수 있는지 말할 수 없다 —
 * 모르면 지우지 않으므로 남은 참조를 판정 불가로 올린다.
 */
private fun Repository.localRefEntries(recorded: ObjectId?): List<PreservedEntry> {
    val refs = localRefs()
    return when {
        refs.isEmpty() -> emptyList()
        recorded == null -> refs.map { ref -> PreservedEntry(ref.name, PreservationReason.UNDECIDABLE) }
        else -> RevWalk(this).use { walk -> refs.mapNotNull { ref -> walk.preservedEntryOf(ref, recorded) } }
    }
}

private fun Repository.localRefs(): List<Ref> =
    refDatabase.getRefsByPrefix(Constants.R_REFS).filter { ref -> ref.objectId != null }

/**
 * 되찾을 수 있으면 null, 아니면 보존 항목. 판정 자체가 되지 않으면(객체가 없거나 커밋이 아니면)
 * 판정 불가로 남긴다 — 실패를 "깨끗함" 으로 읽지 않는다.
 */
private fun RevWalk.preservedEntryOf(ref: Ref, recorded: ObjectId): PreservedEntry? {
    val recoverable = runCatching { reaches(recorded, ref.objectId) }.getOrNull()
        ?: return PreservedEntry(ref.name, PreservationReason.UNDECIDABLE)
    return if (recoverable) null else PreservedEntry(ref.name, ref.preservationReason())
}

/** [target] 이 [from] 에서 도달 가능한가 — 도달 가능하면 기록 커밋 하나로 그 커밋을 되찾을 수 있다. */
private fun RevWalk.reaches(from: ObjectId, target: ObjectId): Boolean =
    isMergedInto(parseCommit(peel(parseAny(target)).id), parseCommit(from))

/** stash 는 어느 브랜치에도 달려 있지 않아 사용자가 해야 할 일이 다르다 — 사유를 접지 않는다. */
private fun Ref.preservationReason(): PreservationReason =
    if (name.startsWith(Constants.R_STASH)) PreservationReason.STASHED else PreservationReason.LOCAL_COMMIT
