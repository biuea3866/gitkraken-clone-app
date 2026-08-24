package dev.undine.infrastructure.git.reflog

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.domain.reflog.ReflogGateway
import dev.undine.domain.reflog.ReflogPage
import dev.undine.infrastructure.git.history.toCommit
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ReflogEntry as JGitReflogEntry
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.internal.storage.file.ObjectDirectory
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException

private const val OPERATION_HEAD = "reflog.headReflog"
private const val OPERATION_REF = "reflog.refReflog"
private const val OPERATION_UNREACHABLE = "reflog.unreachableCommits"
private const val OPERATION_RECOVER = "reflog.recover"

private const val BRANCH_EXISTS = "같은 이름의 브랜치가 이미 있습니다"
private const val REF_MISSING = "옮길 참조가 없습니다"
private const val STALE_CONFIRMATION = "확인한 커밋과 지금 밀려날 커밋이 달라 옮기지 않았습니다"

/**
 * [ReflogGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 스레드 전환을 그 경계가 책임진다. 직접 여는 자원([Git]·[RevWalk])만
 * `use {}` 로 닫는다.
 *
 * **비어 있음을 실패로 만들지 않는다.** 새 저장소에는 reflog 가 없고, 오래된 기록은 만료된다 —
 * 둘 다 정상이므로 [ReflogPage.mayBeExpired] 로 사용자에게 구분해 알린다.
 */
class ReflogGatewayImpl(private val gitAccess: GitAccess) : ReflogGateway {

    override suspend fun headReflog(limit: Int): ReflogPage =
        gitOperation(OPERATION_HEAD) { git -> git.pageOf(Constants.HEAD, limit) }

    override suspend fun refReflog(ref: RefName, limit: Int): ReflogPage =
        gitOperation(OPERATION_REF) { git ->
            // 삭제된 브랜치도 reflog 파일이 남아 있으면 읽힌다 — 그것이 되찾는 단서다.
            if (git.repository.reflogReader(ref.value) == null) {
                throw UndineException.NotFound(UndineException.NotFound.Kind.REF, ref.value)
            }
            git.pageOf(ref.value, limit)
        }

    /**
     * 어떤 참조에서도 닿지 않는 커밋.
     *
     * **느리다** — 참조에서 닿는 전부를 표시한 뒤 객체 DB 를 훑는다. 그래서 계약에서도 별도 진입점이다.
     */
    override suspend fun unreachableCommits(limit: Int): List<Commit> =
        gitOperation(OPERATION_UNREACHABLE) { git ->
            val reachable = git.repository.reachableCommits()
            RevWalk(git.repository).use { walk ->
                git.repository.allObjectIds()
                    .filterNot { id -> id in reachable }
                    .mapNotNull { id -> walk.commitOrNull(id) }
                    .take(limit)
                    .map { revision -> revision.toCommit() }
            }
        }

    override suspend fun recover(at: CommitId, target: RecoveryTarget): RefName =
        gitOperation(OPERATION_RECOVER) { git ->
            val commit = git.repository.requireCommit(at)
            when (target) {
                is RecoveryTarget.NewBranch -> git.createBranchAt(target.name, commit)
                is RecoveryTarget.MoveExisting -> git.moveRefTo(target, commit)
            }
        }

    private suspend fun <T> gitOperation(operation: String, block: (Git) -> T): T =
        try {
            gitAccess.withRepository { repository ->
                // Git.wrap 은 공유 Repository 를 닫지 않는다 — 닫는 것은 Git 자신의 자원뿐이다.
                Git.wrap(repository).use(block)
            }
        } catch (failure: GitAPIException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: JGitInternalException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: IOException) {
            throw UndineException.GitOperationFailed(operation, failure)
        }
}

/**
 * 그 참조의 reflog 한 페이지.
 *
 * reflog 파일이 없거나 항목이 비면 **만료 가능성을 함께** 준다 — 비어 있음이 곧 "그런 일이 없었다"
 * 는 뜻이 아니다(기본 90일 뒤 만료된다).
 */
private fun Git.pageOf(ref: String, limit: Int): ReflogPage {
    val entries = repository.reflogReader(ref)?.getReverseEntries(limit).orEmpty()
    return ReflogPage(
        entries = entries.mapIndexed { index, entry -> entry.toDomain(index) },
        mayBeExpired = entries.isEmpty(),
    )
}

private fun JGitReflogEntry.toDomain(index: Int): ReflogEntry = ReflogEntry(
    index = index,
    // 저장소 최초 항목은 0 해시다 — "이전이 없었다" 를 0 커밋으로 보여주지 않는다.
    from = oldId.takeUnless { it == ObjectId.zeroId() }?.let { CommitId.of(it.name) },
    to = CommitId.of(newId.name),
    action = comment,
    who = who.toPerson(),
    at = who.whenAsInstant,
)

private fun PersonIdent.toPerson(): Person = Person(name = name, email = emailAddress)

/** 새 브랜치를 그 지점에 만든다. 이름이 겹치면 만들지 않는다 — 덮어쓰면 또 잃는다. */
private fun Git.createBranchAt(name: RefName, commit: ObjectId): RefName {
    if (repository.findRef(name.value) != null) {
        throw UndineException.StateViolation("$BRANCH_EXISTS: ${name.value}")
    }
    branchCreate().setName(name.value).setStartPoint(commit.name).call()
    return name
}

/**
 * 기존 참조를 그 지점으로 옮긴다.
 *
 * 확인한 커밋과 지금 밀려날 커밋이 다르면 옮기지 않는다 — 사용자는 **다른 커밋이 밀려난다는 것을
 * 모르고** 확인한 것이다.
 */
private fun Git.moveRefTo(target: RecoveryTarget.MoveExisting, commit: ObjectId): RefName {
    val current = repository.findRef(target.name.value)
        ?: throw UndineException.StateViolation("$REF_MISSING: ${target.name.value}")
    val displaced = current.objectId
    if (displaced == null || displaced.name != target.confirmation.displacedCommit.value) {
        throw UndineException.StateViolation(STALE_CONFIRMATION)
    }
    branchCreate().setName(target.name.value).setStartPoint(commit.name).setForce(true).call()
    return target.name
}

private fun Repository.requireCommit(commit: CommitId): ObjectId {
    val id = resolve(commit.value)
    if (id == null || !objectDatabase.has(id)) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
    }
    return id
}

/** 참조에서 닿는 커밋 전부. 도달 불가 판정의 기준이다. */
private fun Repository.reachableCommits(): Set<ObjectId> = RevWalk(this).use { walk ->
    refDatabase.refs
        .mapNotNull { ref -> ref.objectId }
        .forEach { id -> runCatching { walk.markStart(walk.parseCommit(id)) } }
    walk.map { revision -> revision.id }.toSet()
}

/**
 * 객체 DB 에 있는 객체 전부 — 느슨한 객체 디렉토리와 pack 인덱스를 모두 훑는다.
 *
 * **여기가 느린 지점이다.** JGit 에 "전부 나열" 공개 API 가 없어 `ObjectDirectory` 의 저장 구조를
 * 직접 읽는다. 파일 기반 저장소가 아니면(대안 백엔드) 빈 목록이라 도달 불가 탐색은 조용히 아무것도
 * 찾지 못하는 대신 예외 없이 끝난다.
 */
private fun Repository.allObjectIds(): List<ObjectId> {
    val directory = objectDatabase as? ObjectDirectory ?: return emptyList()
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

/** 커밋이면 파싱해 주고, 트리·블롭이거나 읽을 수 없으면 null. 도달 불가 목록에는 커밋만 담는다. */
private fun RevWalk.commitOrNull(id: ObjectId): RevCommit? =
    runCatching { parseCommit(id) }.getOrNull()

/** 느슨한 객체 디렉토리 이름 길이(해시 앞 2자). */
private const val FANOUT_LENGTH = 2
