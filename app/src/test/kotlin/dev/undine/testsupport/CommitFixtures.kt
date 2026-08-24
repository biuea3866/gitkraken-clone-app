package dev.undine.testsupport

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.HistoryGateway
import dev.undine.domain.Person
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

/** 테스트 기준 시각. 상대 시각 검증이 실행 시각에 흔들리지 않게 고정값만 쓴다 (testing 규칙 7). */
val FIXED_NOW: Instant = Instant.parse("2026-01-01T12:00:00Z")

val FIXTURE_AUTHOR: Person = Person(name = "Undine", email = "undine@example.com")

/** 커밋 해시는 seed 를 40자 hex 로 채워 만든다 — 결정적이고 사람이 읽어도 구분된다. */
fun commitId(seed: Int): CommitId = CommitId.of(seed.toString(16).padStart(40, '0'))

fun commit(
    seed: Int,
    vararg parents: Int,
    message: String = "commit $seed",
    at: Instant = FIXED_NOW,
): Commit = Commit(
    id = commitId(seed),
    parents = parents.map(::commitId),
    message = message,
    author = FIXTURE_AUTHOR,
    committer = FIXTURE_AUTHOR,
    authoredAt = at,
    committedAt = at,
)

/** 앞자리가 서로 다른 해시. 짧은 해시(앞 7자) 표시 검증에 쓴다. */
fun commitIdOf(prefix: String): CommitId = CommitId.of(prefix.padEnd(40, '0'))

fun commitWithId(id: CommitId, message: String, at: Instant = FIXED_NOW): Commit = Commit(
    id = id,
    parents = emptyList(),
    message = message,
    author = FIXTURE_AUTHOR,
    committer = FIXTURE_AUTHOR,
    authoredAt = at,
    committedAt = at,
)

/** 한 번의 [HistoryGateway.load] 호출 인자. 페이징 offset 검증에 쓴다. */
data class HistoryRequest(val refs: List<RefName>, val offset: Int, val limit: Int)

/**
 * 호출 인자를 기록하는 이력 게이트웨이 대역.
 *
 * JGit 을 Mock 으로 대체한 것이 아니라 **도메인 interface 의 대역**이다 — Git 연산 자체는
 * `HistoryGatewayImplSpec` 이 실제 임시 저장소로 검증한다 (testing 규칙 1).
 *
 * @param gate 값이 있으면 [load] 가 여기서 멈춘다. 취소 전파 검증에 쓴다.
 * @param gateFrom [gate] 를 적용할 첫 호출 순번. 기본값 0 은 첫 호출부터 막고, 1 은 첫 페이지는
 *   그냥 통과시키고 **다음 페이지 요청만** 진행 중으로 붙잡는다.
 */
class RecordingHistoryGateway(
    private val commits: List<Commit> = emptyList(),
    private val failure: UndineException? = null,
    private val gate: CompletableDeferred<Unit>? = null,
    private val gateFrom: Int = 0,
) : HistoryGateway {

    private val recorded = mutableListOf<HistoryRequest>()

    val requests: List<HistoryRequest> get() = recorded

    override suspend fun load(refs: List<RefName>, offset: Int, limit: Int): List<Commit> {
        val callIndex = recorded.size
        recorded += HistoryRequest(refs, offset, limit)
        if (callIndex >= gateFrom) gate?.await()
        failure?.let { throw it }
        return commits.drop(offset).take(limit)
    }
}
