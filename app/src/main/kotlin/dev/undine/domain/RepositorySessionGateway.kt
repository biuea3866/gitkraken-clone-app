package dev.undine.domain

/**
 * 세션을 여는 경계가 돌려주는 **정규화된 세션 식별자**.
 *
 * 경로 표기(`./`·심볼릭 링크·상대 경로)는 여럿이지만 같은 저장소면 같은 키다. 정규화는
 * [RepositorySessionGateway] 구현(`RepositoryHolder`) **한 곳에서만** 한다 — 호출부가 자기 나름의
 * 정규화로 세션을 식별하면 별칭 경로에서 다른 탭의 활성 핸들을 회수하게 된다 (결정 C2 정정 3).
 */
@JvmInline
value class RepositorySessionKey(val value: String) {

    override fun toString(): String = value
}

/**
 * **임계 구역 안에서만 유효한** 세션 핸들 조작.
 *
 * [RepositorySessionGateway.transition] 이 주는 동안에만 쓴다. 밖으로 들고 나가 나중에 호출하면
 * 그 호출은 더 이상 직렬화되지 않는다 — 이 타입이 막으려던 교차 전이가 그대로 돌아온다.
 */
interface RepositorySessions {

    /**
     * [path] 세션을 열고 활성 세션으로 만든다. 이미 열려 있으면 그 핸들을 그대로 쓴다.
     *
     * @return 이 저장소의 정규화된 세션 키. 호출부는 회수·되돌리기에서 **이 값만** 식별자로 쓴다.
     */
    suspend fun open(path: RepositoryPath): RepositorySessionKey

    /** [key] 세션의 JGit 핸들을 회수한다. 열려 있지 않으면 아무 일도 하지 않는다. */
    suspend fun release(key: RepositorySessionKey)

    /** 열려 있는 **모든** 세션을 닫는다. */
    suspend fun close()

    /**
     * 열려 있는 세션 집합을 [sessions] 와 **정확히 일치**시키고 [active] 를 활성 세션으로 만든다.
     * 목록에 없는 세션은 회수하고, 목록에 있는데 닫혀 있는 세션은 다시 연다.
     *
     * 실패한 전이를 되돌리기 위한 연산이다. 장부만 되돌리면 그 전이가 새로 연 핸들이 장부 밖에 남아
     * 아무도 회수하지 않는다 — 되돌리기는 **실제 핸들 집합까지** 되돌려야 끝난다.
     *
     * 되살릴 수 없는 세션(그 사이에 사라진 저장소 등)은 실패가 아니라 **결과에서 빠진다** —
     * 호출부의 장부가 그 사실을 그대로 반영해야 장부와 핸들이 다시 갈라지지 않는다.
     *
     * @return 이 호출 뒤 실제로 열려 있는 세션 (인자 순서를 유지한다)
     */
    suspend fun restoreSessions(
        sessions: List<RepositorySessionKey>,
        active: RepositorySessionKey?,
    ): List<RepositorySessionKey>
}

/**
 * 탭마다 하나씩 열리는 **저장소 세션**의 전이 경계.
 *
 * 단일 저장소 계약인 [RepositoryGateway] 와 나눠 둔 이유는 의미가 다르기 때문이다 —
 * [RepositoryGateway.open] 은 "앱이 보는 저장소를 이것으로 바꾼다" 이고, 여기의
 * [RepositorySessions.open] 은 "이 탭의 세션을 열되 다른 탭의 핸들은 건드리지 않는다" 이다.
 * 회수 시점은 호출부(LRU 정책)가 정한다.
 *
 * **직렬화와 원자성은 구현이 소유한다** (`RepositoryHolder` 의 임계구역, 결정 A-N1). 호출부는 자기
 * 락으로 이 계약을 감싸지 않는다 — 감싸는 대신 전이 **전체**를 [transition] 안에서 끝낸다.
 */
interface RepositorySessionGateway {

    /**
     * 세션 전이 하나를 구현의 임계 구역 안에서 수행한다. 같은 Gateway 를 쓰는 다른 전이는
     * [block] 이 끝날 때까지 들어오지 못한다.
     *
     * **여기가 원자성의 경계다.** 핸들 전이뿐 아니라 호출부의 장부 전이와 실패 보상까지 이 안에서
     * 끝내야 한다 (결정 C2 정정 5) — 나누면 앞선 전이의 복원점이 뒤 전이의 상태를 덮는다.
     * 복원점은 [block] 진입 후에 뜨고 [block] 안에서만 유효하다.
     */
    suspend fun <T> transition(block: suspend (RepositorySessions) -> T): T
}
