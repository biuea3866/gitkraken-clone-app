package dev.undine.infrastructure.git.blame

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import dev.undine.domain.UndineException
import dev.undine.domain.blame.BlameGateway
import dev.undine.domain.blame.BlameLine
import dev.undine.domain.blame.BlameResult
import dev.undine.domain.blame.LineRange
import dev.undine.infrastructure.git.history.toCommit
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.blame.BlameResult as JGitBlameResult
import org.eclipse.jgit.diff.DiffConfig
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.FollowFilter
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.IOException

private const val OPERATION_BLAME = "blame.blame"
private const val OPERATION_HISTORY = "blame.fileHistory"

/**
 * [BlameGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 스레드 전환을 그 경계가 책임진다. 직접 여는 자원([Git]·[RevWalk]·[TreeWalk])만
 * `use {}` 로 닫는다.
 *
 * **범위를 좁혀도 JGit 은 파일 전체를 계산한다.** `BlameCommand` 에 범위 옵션이 없어, 계산 후 요청
 * 구간만 잘라 돌려준다. 그래도 범위를 계약에 두는 이유는 화면이 전체를 들고 있지 않게 하고,
 * 나중에 엔진을 바꿀 때 호출부를 고치지 않게 하려는 것이다.
 */
class BlameGatewayImpl(private val gitAccess: GitAccess) : BlameGateway {

    override suspend fun blame(
        path: String,
        range: LineRange,
        ignoreWhitespace: Boolean,
        at: CommitId?,
    ): BlameResult = gitOperation(OPERATION_BLAME) { git ->
        val start = git.repository.startPoint(at)
        git.repository.requirePathAt(path, start)
        if (git.repository.isBinaryAt(path, start)) return@gitOperation BlameResult.Unsupported
        val blamed = git.blame()
            .setFilePath(path)
            .setStartCommit(start)
            .setTextComparator(comparatorFor(ignoreWhitespace))
            .setFollowFileRenames(true)
            .call()
            ?: return@gitOperation BlameResult.Lines(emptyList())
        BlameResult.Lines(blamed.linesIn(range))
    }

    /**
     * **이름 변경을 따라간다.** `LogCommand.addPath` 는 rename 지점에서 이력이 끊겨 파일의 진짜
     * 시작점을 볼 수 없다 — JGit 의 `FollowFilter` 가 걸으면서 경로를 갈아타므로 그것을 쓴다.
     */
    override suspend fun fileHistory(path: String, at: CommitId?, limit: Int): List<Commit> =
        gitOperation(OPERATION_HISTORY) { git ->
            val start = git.repository.startPoint(at)
            git.repository.requirePathAt(path, start)
            RevWalk(git.repository).use { walk ->
                walk.markStart(walk.parseCommit(start))
                walk.treeFilter = FollowFilter.create(path, git.repository.config.get(DiffConfig.KEY))
                walk.take(limit).map { revision -> revision.toCommit() }
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
 * 공백 무시 비교기.
 *
 * 들여쓰기만 바꾼 커밋이 모든 줄의 blame 을 덮어쓰면 실제 작성자를 찾을 수 없다 — 그 커밋을 "변경
 * 없음" 으로 보게 해 원 작성자가 남는다.
 */
private fun comparatorFor(ignoreWhitespace: Boolean): RawTextComparator =
    if (ignoreWhitespace) RawTextComparator.WS_IGNORE_ALL else RawTextComparator.DEFAULT

/** 요청 구간만 잘라 [BlameLine] 으로 옮긴다. 파일 길이를 넘는 요청은 있는 만큼만 준다. */
private fun JGitBlameResult.linesIn(range: LineRange): List<BlameLine> {
    val contents = resultContents ?: return emptyList()
    // 요청이 파일 끝을 넘으면 빈 범위가 되어 비어 있는 목록이 나온다 — 따로 분기하지 않는다.
    val last = if (range.isWhole) contents.size() else minOf(range.end, contents.size())
    return (range.start..last).map { line ->
        val index = line - 1
        BlameLine(
            line = line,
            originLine = getSourceLine(index) + 1,
            commit = CommitId.of(getSourceCommit(index).name),
            author = getSourceAuthor(index).toPerson(),
            content = contents.getString(index),
        )
    }
}

private fun PersonIdent.toPerson(): Person = Person(name = name, email = emailAddress)

/** 기준 커밋. null 이면 HEAD 다 — 저장소에 커밋이 없으면 blame 할 대상 자체가 없다. */
private fun Repository.startPoint(at: CommitId?): ObjectId {
    val raw = at?.value ?: Constants.HEAD
    return resolve(raw) ?: throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, raw)
}

/**
 * 그 커밋에 그 경로가 있는지. 없는 경로를 blame 하면 JGit 이 빈 결과를 주는데, 그것을 그대로
 * 돌려주면 화면이 "변경 이력이 없는 파일" 로 오해한다.
 */
private fun Repository.requirePathAt(path: String, start: ObjectId) {
    if (findBlobAt(path, start) == null) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.PATH, path)
    }
}

/** 줄 개념이 없는 파일인지 — 내용으로 판정한다 (확장자는 근거가 아니다). */
private fun Repository.isBinaryAt(path: String, start: ObjectId): Boolean {
    val blob = findBlobAt(path, start) ?: return false
    return RawText.isBinary(open(blob).cachedBytes)
}

private fun Repository.findBlobAt(path: String, start: ObjectId): ObjectId? =
    RevWalk(this).use { walk ->
        val tree = walk.parseCommit(start).tree
        TreeWalk.forPath(this, path, tree)?.use { treeWalk -> treeWalk.getObjectId(0) }
    }
