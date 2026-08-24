package dev.undine.infrastructure.git.conflict

import dev.undine.domain.UndineException
import dev.undine.domain.conflict.ConflictGateway
import dev.undine.domain.conflict.ConflictSide
import dev.undine.domain.conflict.ConflictedFile
import dev.undine.infrastructure.git.ref.translatingGitFailure
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.diff.RawText
import java.io.File

private const val OPERATION_LIST = "conflict.listConflicted"
private const val OPERATION_READ = "conflict.readConflicted"
private const val OPERATION_RESOLVE = "conflict.resolve"
private const val OPERATION_RESOLVE_BINARY = "conflict.resolveBinary"

/**
 * 인덱스가 충돌 항목을 담는 stage 번호. git 이 정하는 값이다.
 *
 * stage 1 = base(공통 조상) · 2 = ours(HEAD) · 3 = theirs(가져온 쪽).
 */
private const val STAGE_OURS = DirCacheEntry.STAGE_2
private const val STAGE_THEIRS = DirCacheEntry.STAGE_3

/**
 * [ConflictGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — 동시 접근 직렬화와 IO 스레드 전환을
 * 그 경계가 책임진다. 직접 여는 JGit 자원([Git])만 `use {}` 로 닫는다.
 *
 * **해결은 워킹트리와 인덱스를 함께 갱신한다.** 인덱스만 올리면 파일에 표식이 남은 채 해결된 것으로
 * 보이고, 워킹트리만 쓰면 `continue` 가 미해결로 막힌다.
 */
class ConflictGatewayImpl(private val gitAccess: GitAccess) : ConflictGateway {

    override suspend fun listConflicted(): List<ConflictedFile> =
        gitAccess.withRepository { repository ->
            translatingGitFailure(OPERATION_LIST) {
                Git(repository).use { git ->
                    git.status().call().conflicting.sorted().map { path ->
                        ConflictedFile(path = path, isBinary = repository.looksBinary(path))
                    }
                }
            }
        }

    override suspend fun readConflicted(path: String): String =
        gitAccess.withRepository { repository ->
            translatingGitFailure(OPERATION_READ) {
                repository.requireConflicted(path)
                File(repository.workTree, path).readText()
            }
        }

    override suspend fun resolve(path: String, content: String) {
        gitAccess.withRepository { repository ->
            translatingGitFailure(OPERATION_RESOLVE) {
                // 워킹트리를 먼저 쓴다 — 인덱스만 올라간 중간 상태가 남으면 표식이 든 파일이
                // 해결된 것으로 보인다. add 가 실패하면 파일은 고쳐진 채 남아 다시 시도할 수 있다.
                File(repository.workTree, path).writeText(content)
                repository.stageResolved(path)
            }
        }
    }

    override suspend fun resolveBinary(path: String, side: ConflictSide) {
        gitAccess.withRepository { repository ->
            translatingGitFailure(OPERATION_RESOLVE_BINARY) {
                val stage = if (side == ConflictSide.OURS) STAGE_OURS else STAGE_THEIRS
                val bytes = repository.readStage(path, stage)
                    // 한쪽에서 삭제된 충돌은 그 스테이지가 없다 — 없는 것을 빈 파일로 만들지 않는다.
                    ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, "$path@$stage")
                File(repository.workTree, path).writeBytes(bytes)
                repository.stageResolved(path)
            }
        }
    }
}

/** 그 경로가 지금 충돌 중인지. 아니면 읽을 3-way 가 없다. */
private fun Repository.requireConflicted(path: String) {
    val conflicting = Git(this).use { git -> git.status().call().conflicting }
    if (path !in conflicting) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.REF, path)
    }
}

/**
 * 해결된 경로를 인덱스에 올린다. `add` 는 그 경로의 충돌 스테이지(1·2·3)를 지우고 stage 0 으로
 * 바꾸므로, 이것이 곧 "충돌 해결" 기록이다.
 */
private fun Repository.stageResolved(path: String) {
    Git(this).use { git -> git.add().addFilepattern(path).call() }
}

/** 인덱스의 그 스테이지 내용. 없으면 `null`. */
private fun Repository.readStage(path: String, stage: Int): ByteArray? {
    val cache = readDirCache()
    val entry = (0 until cache.entryCount)
        .map(cache::getEntry)
        .firstOrNull { it.pathString == path && it.stage == stage }
        ?: return null
    return newObjectReader().use { reader -> reader.open(entry.objectId).bytes }
}

/**
 * 이진 파일로 보이는지. 워킹트리 파일 앞부분을 본다 — 이진 판정은 내용 기반이고 확장자로는 틀린다.
 *
 * 파일이 없으면(한쪽에서 삭제된 충돌) 이진으로 보지 않는다 — 병합할 내용이 없어 어차피
 * 한쪽 선택만 가능하고, 그 판단은 화면이 스테이지 존재 여부로 한다.
 */
private fun Repository.looksBinary(path: String): Boolean {
    val file = File(workTree, path)
    if (!file.isFile) return false
    return RawText.isBinary(file.readBytes())
}
