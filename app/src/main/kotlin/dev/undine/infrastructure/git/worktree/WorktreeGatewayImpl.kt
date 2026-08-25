package dev.undine.infrastructure.git.worktree

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.worktree.UnsupportedWorktreeMetadata
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.domain.worktree.WorktreeListing
import dev.undine.domain.worktree.WorktreeState
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val OPERATION_LIST = "worktree.list"
private const val OPERATION_ADD = "worktree.add"
private const val OPERATION_REMOVE = "worktree.remove"

/**
 * 검사와 확정 사이의 창. 프로덕션 기본값은 아무것도 하지 않는다.
 *
 * 이 자리가 비어 있으면 "비었는지 확인한 뒤 생긴 파일" · "더티 검사 뒤 생긴 파일" 계약을
 * public [WorktreeGateway] 경로로 증명할 수 없다 — 그 창은 시간으로만 열리기 때문이다.
 * 테스트는 여기에서 파일을 만들어 add/remove 가 사용자 파일을 지우지 않는지 확인한다.
 */
internal typealias CheckWindow = () -> Unit

/**
 * [WorktreeGateway] 의 현행 Git worktree 메타데이터 구현.
 *
 * JGit 7.3.0에는 worktree porcelain API가 없으므로 `.git/worktrees/<name>`의
 * `gitdir`·`commondir`·`HEAD`와 연결 worktree의 `.git` 파일만 직접 다룬다. 이 형식이
 * 바뀌면 추측하지 않고 [WorktreeListing.unsupported]로 보고하는 호환성 경계다.
 *
 * 공유 Repository의 I/O 직렬화와 디스패처 전환은 [GitAccess]가 맡는다. 이 구현은 별도 락이나
 * `withContext`를 만들지 않고, 직접 연 연결 worktree Repository와 Git만 `use {}`로 닫는다.
 */
class WorktreeGatewayImpl internal constructor(
    private val gitAccess: GitAccess,
    private val fileMove: FileMove,
    private val afterCheck: CheckWindow,
) : WorktreeGateway {

    constructor(gitAccess: GitAccess) : this(gitAccess, ATOMIC_FILE_MOVE, {})

    override suspend fun list(): WorktreeListing = gitOperation(OPERATION_LIST) { repository ->
        repository.worktreeListing()
    }

    @Suppress("CyclomaticComplexMethod")
    override suspend fun add(path: RepositoryPath, branch: RefName): Worktree =
        gitOperation(OPERATION_ADD) { repository ->
            val listing = repository.worktreeListing()
            if (listing.unsupported.isNotEmpty()) {
                throw UndineException.StateViolation(
                    "읽을 수 없는 worktree 등록 '${listing.unsupported.first().name}' 이 있어 생성할 수 없습니다",
                )
            }

            val branchRef = repository.findRef(branch.value)
                ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, branch.value)
            if (!branchRef.name.startsWith(Constants.R_HEADS)) {
                throw UndineException.NotFound(UndineException.NotFound.Kind.REF, branch.value)
            }
            if (listing.worktrees.any { it.branch?.value == branchRef.name }) {
                throw UndineException.StateViolation("브랜치 '${branchRef.name}' 은 이미 다른 worktree 에 체크아웃돼 있습니다")
            }

            val target = File(path.value).canonicalFile
            val name = target.name.takeIf { it.isNotBlank() }
                ?: throw UndineException.StateViolation("worktree 등록 이름을 정할 수 없는 경로입니다: '${path.value}'")
            val registration = repository.worktreesDirectory().resolve(name)
            if (Files.exists(registration)) {
                throw UndineException.StateViolation("worktree 등록 '$name' 이 이미 있습니다")
            }
            val targetExisted = target.exists()
            if (targetExisted && (!target.isDirectory || target.list()?.isNotEmpty() != false)) {
                throw UndineException.StateViolation("worktree 경로 '${target.path}' 가 비어 있지 않습니다")
            }

            // 만든 디렉터리를 생성 시점에 기록한다 — 실패 정리는 이 기록만 되돌리고
            // 대상 디렉터리를 재귀 삭제하지 않는다. 비었는지 확인한 뒤에 생긴 사용자 파일이
            // 정리에 휩쓸리면 되돌릴 방법이 없다.
            val createdDirectories = mutableListOf<Path>()
            val gitFile = File(target, GIT_FILE_NAME).toPath()
            try {
                createdDirectories += createMissingDirectories(target.toPath())
                afterCheck()
                placeWorktreeMetadata(registration, gitFile, branchRef.name, fileMove)
                checkoutLinkedWorktree(gitFile.toFile(), branchRef.name)
            } catch (failure: IOException) {
                cleanupFailedAdd(createdDirectories, gitFile, registration).forEach(failure::addSuppressed)
                throw failure
            } catch (failure: GitAPIException) {
                cleanupFailedAdd(createdDirectories, gitFile, registration).forEach(failure::addSuppressed)
                throw failure
            } catch (failure: JGitInternalException) {
                cleanupFailedAdd(createdDirectories, gitFile, registration).forEach(failure::addSuppressed)
                throw failure
            }

            Worktree(
                name = name,
                path = RepositoryPath(target.path),
                branch = RefName(branchRef.name),
                state = WorktreeState.LINKED,
            )
        }

    override suspend fun remove(name: String) {
        gitOperation(OPERATION_REMOVE) { repository ->
            val listing = repository.worktreeListing()
            val worktree = listing.worktrees.firstOrNull { it.name == name }
            if (worktree == null) {
                if (listing.unsupported.any { it.name == name }) {
                    throw UndineException.StateViolation("읽을 수 없는 worktree 등록 '$name' 은 제거할 수 없습니다")
                }
                throw UndineException.NotFound(UndineException.NotFound.Kind.WORKTREE, name)
            }
            if (worktree.state == WorktreeState.MAIN) {
                throw UndineException.StateViolation("메인 worktree '$name' 은 제거할 수 없습니다")
            }
            if (samePath(repository.workTree.toPath(), Path.of(worktree.path.value))) {
                throw UndineException.StateViolation("현재 앱이 연 worktree '$name' 은 제거할 수 없습니다")
            }

            val registration = repository.worktreesDirectory().resolve(name)
            if (worktree.state == WorktreeState.ORPHANED) {
                deleteDirectory(registration)
            } else {
                val worktreeDirectory = Path.of(worktree.path.value)
                // 더티 검사 **직전**의 내용을 기록한다. 기록은 검사가 훑은 범위의 부분집합이므로,
                // 검사 이후에 생긴 파일은 기록에 없어 제거가 지우지 않고 중단한다.
                val recordedContents = worktreeContents(worktreeDirectory)
                val dirtyPaths = dirtyPaths(worktreeDirectory.resolve(GIT_FILE_NAME).toFile())
                if (dirtyPaths.isNotEmpty()) throw UndineException.DirtyWorkingTree(dirtyPaths)
                afterCheck()
                removeLinkedWorktree(worktreeDirectory, registration, recordedContents)
            }
        }
    }

    private suspend fun <T> gitOperation(operation: String, block: (Repository) -> T): T =
        try {
            gitAccess.withRepository(block)
        } catch (failure: GitAPIException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: JGitInternalException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: IOException) {
            throw UndineException.GitOperationFailed(operation, failure)
        }
}
