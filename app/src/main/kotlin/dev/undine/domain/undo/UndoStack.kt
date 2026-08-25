package dev.undine.domain.undo

/**
 * 앱 세션 동안만 살아 있는 되돌리기 스택.
 *
 * **저장소에 남기지 않는다.** 이력은 앱 상태이지 Git 데이터가 아니므로 커밋·ref 로 영속하지 않는다 —
 * 앱을 다시 열면 비어 있는 것이 정상이다.
 *
 * 상한을 두는 이유는 오래 켜 둔 세션에서 기록이 무한히 쌓이는 것을 막기 위해서다. 상한을 넘으면
 * **가장 오래된 항목부터** 밀어낸다 — 사용자가 되돌릴 가능성이 높은 쪽은 최근 연산이다.
 *
 * 기록은 여러 코루틴(각 연산 UseCase)에서 들어오고 되돌리기는 화면에서 들어오므로,
 * 짧은 임계 구역을 잠가 목록이 깨지지 않게 한다.
 */
class UndoStack(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "Undo 스택 상한은 1 이상이어야 합니다: $capacity" }
    }

    private val lock = Any()

    /** 마지막 원소가 가장 최근 기록이다. */
    private val entries = ArrayDeque<OperationEntry>()

    val size: Int
        get() = synchronized(lock) { entries.size }

    fun record(entry: OperationEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /** 되돌릴 다음 항목. 스택을 바꾸지 않는다. */
    fun peek(): OperationEntry? = synchronized(lock) { entries.lastOrNull() }

    /** 최상단 항목을 꺼낸다. 되돌렸든 거부됐든 소비된 항목은 스택에서 사라진다. */
    fun pop(): OperationEntry? = synchronized(lock) { entries.removeLastOrNull() }

    /** 최신 우선 이력. 화면이 그대로 나열할 수 있는 순서다. */
    fun history(): List<OperationEntry> = synchronized(lock) { entries.reversed() }

    companion object {
        /**
         * 기본 상한 50 건. 설정으로 열지 않는다 — 지금 필요하지 않은 유연성이고,
         * 세션 이력이 50 건을 넘어가면 되돌리기보다 `reflog` 로 찾는 편이 맞다.
         */
        const val DEFAULT_CAPACITY: Int = 50
    }
}
