package dev.undine.infrastructure.git.rebase

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.rebase.InteractiveRebaseGateway
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebaseAction
import dev.undine.domain.rebase.RebasePlan
import dev.undine.domain.rebase.RebaseRunProgress
import dev.undine.domain.rebase.RebaseTarget
import dev.undine.infrastructure.git.history.toCommit
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RebaseTodoLine
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.io.IOException
import org.eclipse.jgit.api.RebaseResult as JGitRebaseResult

private const val OPERATION_LIST = "rebase.listTargets"
private const val OPERATION_APPLY = "rebase.apply"
private const val OPERATION_PROGRESS = "rebase.progress"

private const val PLAN_NOT_APPLICABLE = "계획이 규칙을 어겨 리베이스를 시작하지 않았습니다"
private const val ALREADY_IN_PROGRESS = "진행 중인 리베이스가 있어 새로 시작하지 않았습니다"

/**
 * JGit 이 리베이스 진행 상태를 쓰는 디렉토리와 todo 파일. git 본체와 같은 이름이라 외부 git 으로
 * 시작한 리베이스도 같은 경로에서 읽힌다.
 *
 * **진행률은 `msgnum`/`end` 로 읽지 않는다** — git 본체는 그 두 파일을 쓰지만 JGit 은 쓰지 않는다
 * (실측: JGit 7.3 의 rebase-merge 에는 없다). 두 구현이 모두 남기는 `done` 과 `git-rebase-todo` 의
 * 실행 줄 수를 세어 "적용한 수 / 전체" 를 만든다.
 */
private const val REBASE_MERGE_DIR = "rebase-merge"
private const val DONE_FILE = "done"
private const val TODO_FILE = "git-rebase-todo"

/** 원격 추적 참조 접두 — 이 아래에서 닿는 커밋은 이미 push 된 것으로 본다. */
private const val REMOTE_REFS_PREFIX = "refs/remotes/"

/**
 * [InteractiveRebaseGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 스레드 전환을 그 경계가 책임진다. 이 구현은 잠금·스레드 전환을 다시 두지
 * 않고 핸들 수명도 소유하지 않으며, 직접 여는 자원([Git]·[RevWalk])만 `use {}` 로 닫는다.
 *
 * **되돌릴 수 없다.** 리베이스는 커밋을 새로 쓴다 — 끝난 뒤 원래 커밋으로 돌아가려면 진행 중
 * 상태에서 abort(UND-21) 해야 한다. Undo 스택(UND-38)에는 아직 기록하지 않는다.
 */
class InteractiveRebaseGatewayImpl(private val gitAccess: GitAccess) : InteractiveRebaseGateway {

    override suspend fun listTargets(upstream: RefName): List<RebaseTarget> =
        gitOperation(OPERATION_LIST) { git ->
            val repository = git.repository
            val upstreamId = repository.requireCommit(upstream)
            val head = repository.resolve(Constants.HEAD) ?: return@gitOperation emptyList()
            RevWalk(repository).use { walk ->
                val pushedTips = repository.remoteTrackingTips(walk)
                walk.markStart(walk.parseCommit(head))
                walk.markUninteresting(walk.parseCommit(upstreamId))
                // RevWalk 은 최신부터 준다 — todo 순서(오래된 것부터)로 뒤집는다.
                walk.toList().reversed().map { revision ->
                    RebaseTarget(
                        commit = revision.toCommit(),
                        isPushed = pushedTips.any { tip -> walk.isMergedInto(revision, tip) },
                    )
                }
            }
        }

    /**
     * `prepareSteps` 에서 todo 목록을 **계획으로 통째로 교체한다.** JGit 이 만든 기본 목록을
     * 부분 수정하면(`setAction`) 허용되지 않는 전이에서 예외가 나고 drop 을 표현할 수 없다.
     */
    override suspend fun apply(upstream: RefName, plan: RebasePlan): InteractiveRebaseOutcome =
        gitOperation(OPERATION_APPLY) { git ->
            if (!plan.isApplicable) throw UndineException.StateViolation(PLAN_NOT_APPLICABLE)
            if (git.repository.rebaseDirectory().isDirectory) {
                throw UndineException.StateViolation(ALREADY_IN_PROGRESS)
            }
            // 적용할 줄이 없으면 저장소를 건드리지 않는다 — JGit 은 이 경우 FAST_FORWARD 를 돌려주어
            // "아무것도 안 했다" 와 "빨리 감기로 옮겼다" 가 구분되지 않는다.
            if (plan.steps.isEmpty()) return@gitOperation InteractiveRebaseOutcome.NothingToDo
            val upstreamId = git.repository.requireCommit(upstream)
            git.rebase()
                .setUpstream(upstreamId)
                .runInteractively(PlanHandler(plan))
                .call()
                .toOutcome(git)
        }

    override suspend fun progress(): RebaseRunProgress? =
        gitOperation(OPERATION_PROGRESS) { git ->
            val directory = git.repository.rebaseDirectory()
            if (!directory.isDirectory) return@gitOperation null
            val applied = directory.countTodoLines(DONE_FILE)
            val total = applied + directory.countTodoLines(TODO_FILE)
            if (total == 0) null else RebaseRunProgress(applied, total)
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
 * 계획을 JGit todo 목록으로 옮기는 핸들러.
 *
 * [modifyCommitMessage] 는 어느 커밋의 메시지인지 알려주지 않는다 (JGit 계약이 문자열 하나다).
 * 그래서 **받은 메시지로 계획의 reword 줄을 찾는다** — 못 찾으면 원문을 그대로 돌려주어, 매칭
 * 실패가 "메시지를 조용히 바꿔버리는" 사고로 번지지 않게 한다. squash 가 합친 메시지도 이 경로로
 * 들어오는데, 어떤 reword 와도 일치하지 않으므로 그대로 유지된다.
 */
private class PlanHandler(private val plan: RebasePlan) : RebaseCommand.InteractiveHandler {

    override fun prepareSteps(steps: MutableList<RebaseTodoLine>) {
        steps.clear()
        plan.steps.forEach { step ->
            val action = step.action.toTodoAction() ?: return@forEach
            steps += RebaseTodoLine(
                action,
                AbbreviatedObjectId.fromObjectId(ObjectId.fromString(step.commit.id.value)),
                step.commit.message.firstLine(),
            )
        }
    }

    override fun modifyCommitMessage(message: String): String {
        val reword = plan.steps.firstNotNullOfOrNull { step ->
            (step.action as? RebaseAction.Reword)?.takeIf { step.commit.message.matchesRequested(message) }
        }
        return reword?.message ?: message
    }
}

/** drop 은 todo 줄이 없는 것으로 표현한다 — git 도 줄을 지워 커밋을 뺀다. */
private fun RebaseAction.toTodoAction(): RebaseTodoLine.Action? = when (this) {
    RebaseAction.Pick -> RebaseTodoLine.Action.PICK
    is RebaseAction.Reword -> RebaseTodoLine.Action.REWORD
    RebaseAction.Edit -> RebaseTodoLine.Action.EDIT
    RebaseAction.Squash -> RebaseTodoLine.Action.SQUASH
    RebaseAction.Fixup -> RebaseTodoLine.Action.FIXUP
    RebaseAction.Drop -> null
}

/**
 * JGit 이 넘긴 메시지가 이 커밋의 것인지. 전문 일치와 첫 줄 일치를 모두 본다 — JGit 이 todo 줄에
 * 담은 짧은 메시지를 되돌려주는 경우가 있어 전문만 비교하면 놓친다.
 */
private fun String.matchesRequested(requested: String): Boolean {
    val trimmedRequested = requested.trim()
    return trim() == trimmedRequested || firstLine() == trimmedRequested
}

private fun String.firstLine(): String = lineSequence().firstOrNull().orEmpty()

/** 충돌·멈춤은 실패가 아니라 결과다 — 예외로 올리면 화면이 이어갈 수단을 잃는다. */
private fun JGitRebaseResult.toOutcome(git: Git): InteractiveRebaseOutcome = when (status) {
    JGitRebaseResult.Status.OK, JGitRebaseResult.Status.FAST_FORWARD -> InteractiveRebaseOutcome.Completed
    JGitRebaseResult.Status.UP_TO_DATE, JGitRebaseResult.Status.NOTHING_TO_COMMIT ->
        InteractiveRebaseOutcome.NothingToDo

    JGitRebaseResult.Status.STOPPED, JGitRebaseResult.Status.CONFLICTS ->
        InteractiveRebaseOutcome.Conflicted(git.conflictedPaths())

    JGitRebaseResult.Status.EDIT -> InteractiveRebaseOutcome.StoppedForEdit(
        currentCommit?.let { CommitId.of(it.name) },
    )

    else -> throw UndineException.StateViolation("리베이스가 예상하지 못한 상태로 멈췄습니다: $status")
}

private fun Git.conflictedPaths(): List<String> = status().call().conflicting.sorted()

private fun Repository.rebaseDirectory(): File = File(directory, REBASE_MERGE_DIR)

/** todo 파일의 실행 줄 수. 주석(`#`)과 빈 줄은 지시가 아니라 세지 않는다. */
private fun File.countTodoLines(name: String): Int =
    File(this, name).takeIf { it.isFile }
        ?.readLines()
        ?.count { line -> line.isNotBlank() && !line.trimStart().startsWith("#") }
        ?: 0

/** 참조 부재와 "참조는 있는데 커밋이 없음" 을 구분한다. */
private fun Repository.requireCommit(target: RefName): ObjectId =
    resolve(target.value)
        ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, target.value)

/** 원격 추적 참조의 끝 커밋들. 여기서 닿는 커밋은 이미 원격에 올라간 것이다. */
private fun Repository.remoteTrackingTips(walk: RevWalk): List<RevCommit> =
    refDatabase.getRefsByPrefix(REMOTE_REFS_PREFIX)
        .mapNotNull { ref -> ref.objectId }
        .mapNotNull { id -> runCatching { walk.parseCommit(id) }.getOrNull() }
