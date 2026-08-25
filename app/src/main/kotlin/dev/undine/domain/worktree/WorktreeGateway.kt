package dev.undine.domain.worktree

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException

/**
 * 하나의 저장소를 **여러 디렉터리에 동시 체크아웃**하는 git worktree 를 다룬다.
 * 브랜치를 오갈 때 stash 하지 않아도 되는 것이 이 기능의 실질적 이점이다.
 *
 * 이름이 비슷한 `WorktreeOpsGateway` 는 stash·reset·revert 를 다루는 **다른 계약**이다.
 *
 * ### 지원 범위
 * 조회·생성·제거 셋뿐이다. move·prune·lock 은 이 계약에 없다 — 저수준 git 메타데이터를
 * 직접 다루는 구현이라 범위를 좁혀 위험을 줄인 결정이다.
 *
 * 강제 제거(`force`)도 없다. 더티한 worktree 는 **항상** 거부한다 — 커밋되지 않은 변경을
 * 지우는 파괴적 연산은 확인 흐름을 갖춘 화면과 함께 별도로 다룬다.
 */
interface WorktreeGateway {

    /**
     * 이 저장소에 딸린 worktree 를 모두 조회한다.
     *
     * 메인 worktree 와 디렉터리가 사라진 고아 등록을 [WorktreeState] 로 구분하고,
     * 읽을 수 없는 등록은 [WorktreeListing.unsupported] 로 따로 보고한다.
     */
    suspend fun list(): WorktreeListing

    /**
     * [branch] 를 [path] 에 새 worktree 로 체크아웃한다. 등록 이름은 [path] 의 마지막 구성요소다.
     *
     * **없는 브랜치를 만들어 주지 않는다** — 브랜치 생성은 `RefGateway` 의 일이고, 오타를
     * 새 브랜치로 굳혀 버리면 사용자가 알아채기 어렵다.
     *
     * @throws UndineException.NotFound [branch] 가 없을 때 ([UndineException.NotFound.Kind.REF])
     * @throws UndineException.StateViolation 이미 다른 worktree 가 그 브랜치를 체크아웃했거나,
     *   [path] 가 비어 있지 않거나, 같은 이름의 등록이 이미 있을 때
     */
    suspend fun add(path: RepositoryPath, branch: RefName): Worktree

    /**
     * [name] worktree 의 디렉터리와 등록을 지운다.
     *
     * @throws UndineException.NotFound 그 이름의 worktree 가 없을 때
     *   ([UndineException.NotFound.Kind.WORKTREE])
     * @throws UndineException.StateViolation 메인 worktree, 앱이 현재 연 worktree,
     *   또는 읽을 수 없는 등록을 지우려 할 때
     * @throws UndineException.DirtyWorkingTree 대상에 커밋되지 않은 변경이 있을 때
     */
    suspend fun remove(name: String)
}
