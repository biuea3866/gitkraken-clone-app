package dev.undine.infrastructure.git.history

import dev.undine.domain.Commit
import dev.undine.domain.HistoryGateway
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.errors.RevisionSyntaxException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException
import kotlin.coroutines.CoroutineContext

private const val OPERATION = "history.load"

/**
 * JGit `RevWalk` 로 커밋 이력을 페이징 조회한다.
 *
 * - 공유 JGit `Repository` 에는 [gitAccess] 를 통해서만 접근한다. `Repository` 는 스레드 안전하지
 *   않으므로 직렬화와 `Dispatchers.IO` 실행은 [GitAccess] 가 책임진다 — 이 클래스는 락이나
 *   `withContext` 를 다시 걸지 않고, 핸들의 수명·닫기도 소유하지 않는다.
 * - `refs` 가 비면 **빈 리스트**를 반환한다. 화면이 "표시할 커밋 없음" 으로 처리한다.
 * - 정렬은 **위상 우선 + 커밋 시각 역순**이다. 시각만으로 정렬하면 시계가 어긋난 커밋 때문에
 *   부모가 자식보다 앞서는 그래프가 나온다.
 * - 반환 목록은 항상 `limit` 이하다. 다만 JGit 의 위상 정렬 제너레이터는 정렬 특성상
 *   도달 가능한 커밋을 내부 큐에 모으므로, 메모리 절약 효과는 **반환 목록 크기에 한정**된다.
 *
 * [openRevWalk] 는 조회 단위로 `RevWalk` 를 여는 팩토리다. 기본값이 프로덕션 경로이며,
 * 테스트가 닫힘·실패·취소를 관찰하기 위해서만 교체한다.
 */
class HistoryGatewayImpl(
    private val gitAccess: GitAccess,
    private val openRevWalk: (Repository) -> RevWalk = { target -> RevWalk(target) },
) : HistoryGateway {

    override suspend fun load(refs: List<RefName>, offset: Int, limit: Int): List<Commit> {
        require(offset >= 0) { "offset 은 0 이상이어야 합니다: $offset" }
        require(limit > 0) { "limit 은 1 이상이어야 합니다: $limit" }
        if (refs.isEmpty()) return emptyList()

        // GitAccess 의 임계 구역은 일반 람다라 여기서 호출자 컨텍스트를 잡아 취소를 확인한다.
        val caller = currentCoroutineContext()
        return gitAccess.withRepository { repository -> loadPage(repository, caller, refs, offset, limit) }
    }

    private fun loadPage(
        repository: Repository,
        caller: CoroutineContext,
        refs: List<RefName>,
        offset: Int,
        limit: Int,
    ): List<Commit> =
        try {
            openRevWalk(repository).use { walk ->
                walk.startFrom(repository, refs)
                walk.skipCommits(caller, offset)
                walk.takeCommits(caller, limit)
            }
        } catch (cause: IOException) {
            throw UndineException.GitOperationFailed(OPERATION, cause)
        }
}

private fun RevWalk.startFrom(repository: Repository, refs: List<RefName>) {
    sort(RevSort.TOPO)
    sort(RevSort.COMMIT_TIME_DESC, true)
    refs.forEach { ref -> markStart(parseCommit(repository.resolveRef(ref))) }
}

private fun Repository.resolveRef(ref: RefName): ObjectId {
    val resolved = try {
        resolve(ref.value)
    } catch (ignored: RevisionSyntaxException) {
        // 형식이 깨진 참조는 사용자 입장에서 "없는 참조" 와 같다 — 형식 검증은 RefGateway 소유다.
        null
    }
    return resolved ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, ref.value)
}

private fun RevWalk.skipCommits(caller: CoroutineContext, count: Int) {
    repeat(count) {
        caller.ensureActive()
        if (next() == null) return
    }
}

private fun RevWalk.takeCommits(caller: CoroutineContext, limit: Int): List<Commit> {
    val page = mutableListOf<Commit>()
    while (page.size < limit) {
        caller.ensureActive()
        val revCommit = next() ?: break
        page += revCommit.toCommit()
    }
    return page
}
