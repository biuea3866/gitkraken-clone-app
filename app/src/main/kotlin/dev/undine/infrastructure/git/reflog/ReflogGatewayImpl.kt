package dev.undine.infrastructure.git.reflog

import dev.undine.domain.CommitId
import dev.undine.domain.Person
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.reflog.RecoveredRef
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.domain.reflog.ReflogGateway
import dev.undine.domain.reflog.ReflogPage
import dev.undine.domain.reflog.UnreachableCommitScan
import dev.undine.infrastructure.git.ref.baselineHeld
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ReflogReader
import org.eclipse.jgit.lib.ReflogEntry as JGitReflogEntry
import org.eclipse.jgit.lib.Repository
import java.io.IOException

private const val OPERATION_HEAD = "reflog.headReflog"
private const val OPERATION_REF = "reflog.refReflog"
private const val OPERATION_UNREACHABLE = "reflog.unreachableCommits"
private const val OPERATION_RECOVER = "reflog.recover"

/**
 * [ReflogGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 스레드 전환을 그 경계가 책임진다. 직접 여는 자원([Git]·`RevWalk`)만
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
            // 없는 참조를 빈 페이지로 돌려주면 화면이 "움직인 적 없는 브랜치" 로 오해한다.
            if (git.repository.reflogReaderOf(ref.value) == null) {
                throw UndineException.NotFound(UndineException.NotFound.Kind.REF, ref.value)
            }
            git.pageOf(ref.value, limit)
        }

    override suspend fun unreachableCommits(limit: Int): UnreachableCommitScan =
        gitOperation(OPERATION_UNREACHABLE) { git -> git.repository.scanUnreachableCommits(limit) }

    /**
     * 복구 직후의 기준 상태를 **복구와 같은 임계 구역 안에서** 캡처해 결과에 함께 담는다. 되돌리기를
     * 기록하는 호출자(`RecoveryActionService`)가 그 값을 밖에서 따로 읽으면 그 사이에 낀 앱 내부의
     * 다른 조작까지 반영된 상태가 기록된다 (UND-73).
     */
    override suspend fun recover(at: CommitId, target: RecoveryTarget): RecoveredRef =
        gitOperation(OPERATION_RECOVER) { git ->
            val recovered = git.recoverAt(git.repository.requireCommit(at), target)
            RecoveredRef(recovered, git.repository.baselineHeld())
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
 * 항목이 비면 **만료 가능성을 함께** 준다 — 비어 있음이 곧 "그런 일이 없었다" 는 뜻이 아니다
 * (기본 90일 뒤 만료된다).
 */
private fun Git.pageOf(ref: String, limit: Int): ReflogPage {
    val entries = repository.reflogReaderOf(ref)?.getReverseEntries(limit).orEmpty()
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

/**
 * 그 이름의 reflog 를 읽는 도구. 참조 자체가 없으면 null 이다.
 *
 * `Ref` 를 받는 overload 를 쓰는 이유는 이름을 받는 쪽이 JGit 7 에서 폐기됐기 때문이다 — 동작은 같다.
 */
internal fun Repository.reflogReaderOf(ref: String): ReflogReader? =
    findRef(ref)?.let { found -> getReflogReader(found) }
