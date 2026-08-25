package dev.undine.infrastructure.git.reflog

import dev.undine.domain.reflog.UnreachableCommitScan
import dev.undine.infrastructure.git.history.toCommit
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.internal.storage.file.ObjectDirectory
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk

/** 느슨한 객체 디렉토리 이름 길이(해시 앞 2자). */
private const val FANOUT_LENGTH = 2

/**
 * 어떤 참조에서도 닿지 않고 reflog 에도 없는 커밋을 최대 [limit] 건 찾는다.
 *
 * **느리다** — 참조에서 닿는 전부와 reflog 에 적힌 전부를 걸러낸 뒤 객체 DB 전체를 훑는다. 그래서
 * 계약에서도 reflog 조회와 별도 진입점이다.
 */
internal fun Repository.scanUnreachableCommits(limit: Int): UnreachableCommitScan {
    val candidates = allObjectIds()
        ?: return UnreachableCommitScan.NotSupported(
            UnreachableCommitScan.NotSupported.Reason.NON_FILE_OBJECT_DATABASE,
        )
    val known = reachableCommits() + reflogCommits()
    return RevWalk(this).use { walk ->
        UnreachableCommitScan.Scanned(
            candidates
                .filterNot { id -> id in known }
                .mapNotNull { id -> walk.commitCandidateOrNull(id) }
                .take(limit)
                .map { revision -> revision.toCommit() },
        )
    }
}

/** 참조에서 닿는 커밋 전부. 도달 불가 판정의 기준이다. */
private fun Repository.reachableCommits(): Set<ObjectId> = RevWalk(this).use { walk ->
    // detached HEAD 는 refs/ 아래에 없을 수 있다. 빠뜨리면 현재 체크아웃한 커밋을 유실 후보로
    // 잘못 보고하므로, 모든 저장소에서 HEAD 를 명시적인 시작점으로 넣는다.
    val starts = listOfNotNull(resolve(Constants.HEAD)) + refDatabase.refs.mapNotNull { ref ->
        val peeled = refDatabase.peel(ref)
        peeled.peeledObjectId ?: peeled.objectId
    }
    starts
        // 태그는 가리키는 커밋으로 벗겨서 포함하고, 블롭을 가리키는 참조는 건너뛴다.
        .mapNotNull { id -> walk.peeledCommitOrNull(id) }
        .forEach { commit -> walk.markStart(commit) }
    walk.map { revision -> revision.id }.toSet()
}

/**
 * reflog 에 적힌 커밋 전부 — HEAD 와 살아 있는 모든 참조의 기록.
 *
 * 이 커밋들은 reflog 조회로 이미 되찾을 수 있으므로 도달 불가 목록에서 제외한다. 그래야 이 진입점이
 * **reflog 로는 찾을 수 없는 것**만 돌려준다.
 */
private fun Repository.reflogCommits(): Set<ObjectId> {
    val refNames = listOf(Constants.HEAD) + refDatabase.refs.map { ref -> ref.name }
    return refNames.flatMap { name -> reflogReaderOf(name)?.reverseEntries.orEmpty() }
        .flatMap { entry -> listOf(entry.oldId, entry.newId) }
        .filterNot { id -> id == ObjectId.zeroId() }
        .toSet()
}

/**
 * 객체 DB 에 있는 객체 전부 — 느슨한 객체 디렉토리와 pack 인덱스를 모두 훑는다. 나열할 수 없는
 * 저장 방식이면 null 이다.
 *
 * **여기가 느린 지점이다.** JGit 에 "전부 나열" 공개 API 가 없어 `ObjectDirectory` 의 저장 구조를
 * 직접 읽는다. 파일 기반이 아닌 저장소는 빈 목록이 아니라 null 로 알린다 — 빈 목록이면 호출부가
 * "도달 불가 커밋 없음" 으로 오해한다.
 */
private fun Repository.allObjectIds(): List<ObjectId>? {
    val directory = objectDatabase as? ObjectDirectory ?: return null
    val loose = directory.directory.listFiles { file -> file.isDirectory && file.name.length == FANOUT_LENGTH }
        .orEmpty()
        .flatMap { fanout ->
            fanout.listFiles().orEmpty().mapNotNull { file ->
                runCatching { ObjectId.fromString(fanout.name + file.name) }.getOrNull()
            }
        }
    val packed = directory.packs.flatMap { pack -> pack.map { entry -> entry.toObjectId() } }
    return (loose + packed).distinct()
}

/**
 * 도달성 **시작점**으로 쓸 커밋. 태그를 벗겨 가리키는 커밋을 준다.
 *
 * 참조는 태그를 가리킬 수 있고 그때 도달성의 뿌리는 태그가 아니라 벗긴 커밋이다. 커밋으로 벗길 수
 * 없는 것(블롭을 가리키는 참조)은 실패가 아니라 **걸러낼 대상**이므로 예외 대신 null 로 다룬다.
 * 그 외 IO 실패는 삼키지 않고 그대로 올린다.
 */
private fun RevWalk.peeledCommitOrNull(id: ObjectId): RevCommit? =
    try {
        parseCommit(id)
    } catch (_: IncorrectObjectTypeException) {
        null
    } catch (_: MissingObjectException) {
        null
    }

/**
 * 그 객체 **자신이** 커밋일 때만 준다 — 태그를 벗기지 않는다.
 *
 * 벗기면 annotated tag 객체 하나 때문에 그 태그가 가리키는(따라서 도달 가능한) 커밋이 유실 후보로
 * 올라온다. 태그 객체는 참조에서 닿아도 커밋 도달 집합에는 없으므로 후보 필터를 그대로 통과하는데,
 * 그때 벗기면 멀쩡한 커밋을 "잃어버렸다" 고 보고하게 된다.
 */
private fun RevWalk.commitCandidateOrNull(id: ObjectId): RevCommit? =
    try {
        parseAny(id) as? RevCommit
    } catch (_: MissingObjectException) {
        null
    }
