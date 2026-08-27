package dev.undine.infrastructure.git.ref

import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.Tag
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.errors.NotMergedException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/**
 * [RefGateway] 의 JGit 구현.
 *
 * JGit `Repository` 는 스레드 안전하지 않으므로 핸들에 직접 손대지 않고 [GitAccess] 를 통해서만
 * 접근한다 — 직렬화(`Mutex`)와 `Dispatchers.IO` 전환은 그 경계가 책임진다. 따라서 이 클래스는
 * 자기 안에서 락도 `withContext` 도 걸지 않는다. 핸들의 수명은 저장소 홀더(UND-02)가 소유하므로
 * 이 클래스는 핸들을 닫지 않고, 자기가 새로 여는 JGit 자원만 `use {}` 로 닫는다.
 * 배선은 UND-26 이 한다.
 *
 * ref 이름은 짧은 형태(`main`·`origin/feature`)로 반환하고, 입력은 짧은 형태와
 * 전체 형태(`refs/heads/main`) 를 모두 받는다. 형식 검증은 [Repository.isValidRefName] 에 위임한다.
 *
 * 태그는 조회·생성·삭제만 제공한다. 이름 변경은 git 에서 삭제+재생성이라 이미 push 된 태그에서
 * 위험하므로 계약 자체에 없다.
 */
class RefGatewayImpl(private val gitAccess: GitAccess) : RefGateway {

    /**
     * 로컬·원격 브랜치를 함께 반환한다. 심볼릭 ref(`refs/remotes/origin/HEAD`)는 브랜치가 아니라
     * 기본 브랜치를 가리키는 포인터이므로 제외한다. 커밋이 0건인 저장소는 빈 목록이다.
     */
    override suspend fun listBranches(): List<Branch> = gitAccess.withRepository { repository ->
        translatingGitFailure("ref.listBranches") {
            val currentFullRef = repository.fullBranch
            Git.wrap(repository).use { git ->
                git.branchList()
                    .setListMode(ListBranchCommand.ListMode.ALL)
                    .call()
                    .filterNot { it.isSymbolic }
                    .map { repository.toBranch(it, currentFullRef) }
            }
        }
    }

    override suspend fun listTags(): List<Tag> = gitAccess.withRepository { repository ->
        translatingGitFailure("ref.listTags") {
            Git.wrap(repository).use { git ->
                RevWalk(repository).use { walk ->
                    git.tagList().call().map { walk.toTag(it) }
                }
            }
        }
    }

    override suspend fun createTag(name: RefName, at: CommitId, message: String?): Tag {
        val fullRef = validatedTagRef(name)
        return gitAccess.withRepository { repository ->
            translatingGitFailure("ref.createTag") {
                if (repository.exactRef(fullRef) != null) {
                    throw UndineException.StateViolation("이미 존재하는 태그 이름입니다: ${name.value}")
                }
                Git.wrap(repository).use { git ->
                    RevWalk(repository).use { walk ->
                        val created = git.tag()
                            .setName(Repository.shortenRefName(fullRef))
                            .setObjectId(walk.commitOf(at))
                            .setAnnotated(message != null)
                            .setMessage(message)
                            .call()
                        walk.toTag(created)
                    }
                }
            }
        }
    }

    override suspend fun deleteTag(name: RefName) {
        val fullRef = validatedTagRef(name)
        gitAccess.withRepository { repository ->
            translatingGitFailure("ref.deleteTag") {
                if (repository.exactRef(fullRef) == null) {
                    throw UndineException.NotFound(UndineException.NotFound.Kind.REF, name.value)
                }
                Git.wrap(repository).use { git -> git.tagDelete().setTags(fullRef).call() }
            }
        }
    }

    override suspend fun createBranch(name: RefName, at: CommitId): Branch {
        val fullRef = validatedBranchRef(name)
        return gitAccess.withRepository { repository ->
            translatingGitFailure("ref.createBranch") {
                if (repository.exactRef(fullRef) != null) {
                    throw UndineException.StateViolation("이미 존재하는 브랜치 이름입니다: ${name.value}")
                }
                Git.wrap(repository).use { git ->
                    RevWalk(repository).use { walk ->
                        val created = git.branchCreate()
                            .setName(Repository.shortenRefName(fullRef))
                            .setStartPoint(walk.commitOf(at))
                            .call()
                        repository.toBranch(created, repository.fullBranch)
                    }
                }
            }
        }
    }

    /**
     * 현재 브랜치의 이름 변경도 허용한다. 업스트림 설정은 JGit `RenameBranchCommand` 가 따라 옮기고,
     * 원격 추적 브랜치는 건드리지 않는다 — 원격의 이름을 바꾸는 것은 push 의 몫이다.
     */
    override suspend fun renameBranch(from: RefName, to: RefName) {
        val fromFullRef = validatedBranchRef(from)
        val toFullRef = validatedBranchRef(to)
        gitAccess.withRepository { repository ->
            translatingGitFailure("ref.renameBranch") {
                if (repository.exactRef(fromFullRef) == null) {
                    throw UndineException.NotFound(UndineException.NotFound.Kind.REF, from.value)
                }
                if (repository.exactRef(toFullRef) != null) {
                    throw UndineException.StateViolation("이미 존재하는 브랜치 이름입니다: ${to.value}")
                }
                Git.wrap(repository).use { git ->
                    git.branchRename()
                        .setOldName(fromFullRef)
                        .setNewName(Repository.shortenRefName(toFullRef))
                        .call()
                }
            }
        }
    }

    /**
     * 미병합 브랜치는 [force] 없이 삭제하지 않고 [DeleteBranchResult.REFUSED_UNMERGED] 를 반환한다 —
     * UI 가 무엇을 잃게 되는지 경고한 뒤 `force = true` 로 재시도하게 하는 것이 이 계약의 목적이다.
     * 현재 체크아웃된 브랜치는 [force] 여부와 무관하게 거부한다.
     */
    override suspend fun deleteBranch(name: RefName, force: Boolean): DeleteBranchResult {
        val fullRef = validatedBranchRef(name)
        return gitAccess.withRepository { repository ->
            translatingGitFailure("ref.deleteBranch") {
                if (repository.exactRef(fullRef) == null) {
                    throw UndineException.NotFound(UndineException.NotFound.Kind.REF, name.value)
                }
                if (repository.fullBranch == fullRef) {
                    throw UndineException.StateViolation("현재 체크아웃된 브랜치는 삭제할 수 없습니다: ${name.value}")
                }
                try {
                    Git.wrap(repository).use { git ->
                        git.branchDelete().setBranchNames(fullRef).setForce(force).call()
                    }
                    DeleteBranchResult.DELETED
                } catch (ignoredNotMerged: NotMergedException) {
                    DeleteBranchResult.REFUSED_UNMERGED
                }
            }
        }
    }

    /**
     * 더티 워킹트리에서 [force] 가 false 면 [UndineException.DirtyWorkingTree] 로 거부한다 —
     * 강제 체크아웃은 사용자의 편집을 되돌릴 수 없이 지우므로 기본값이 될 수 없다.
     */
    override suspend fun checkout(ref: RefName, force: Boolean) {
        gitAccess.withRepository { repository ->
            translatingGitFailure("ref.checkout") {
                Git.wrap(repository).use { git -> git.checkoutHeld(ref, force) }
            }
        }
    }

    /**
     * 조건부 갱신이라 **읽기와 쓰기가 한 임계 구역 안**에 있어야 한다 — 기대 위치를 밖에서 읽어
     * 넘기면 그 사이의 이동을 놓친다. 검사와 갱신은 [moveBranchHeld] 가 한 번에 한다.
     */
    override suspend fun moveBranch(branch: RefName, to: CommitId, expected: CommitId): RepositoryBaseline =
        gitAccess.withRepository { repository ->
            translatingGitFailure("ref.moveBranch") {
                Git.wrap(repository).use { git -> git.moveBranchHeld(branch, to, expected) }
                // 갱신과 **같은** 임계 구역에서 읽는다 — 블록을 나간 뒤 읽으면 그 사이의 다른 조작이 섞인다.
                repository.baselineHeld()
            }
        }

    /**
     * [moveBranch] 와 같이 갱신 직후의 기준 상태를 같은 구역에서 캡처해 돌려준다 — 태그 이동을
     * 기록하는 호출자(`ExecuteGraphOperationUseCase`)가 그 값을 따로 읽지 않게 하려는 것이다.
     */
    override suspend fun moveTag(tag: RefName, to: CommitId, expected: CommitId): RepositoryBaseline =
        gitAccess.withRepository { repository ->
            translatingGitFailure("ref.moveTag") {
                Git.wrap(repository).use { git -> git.moveTagHeld(tag, to, expected) }
                repository.baselineHeld()
            }
        }
}
