package dev.undine.domain.blame

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import dev.undine.domain.UndineException

/**
 * blame 한 줄 — 그 줄을 마지막으로 고친 커밋.
 *
 * @param line 1부터 세는 줄 번호. 화면이 코드와 나란히 놓는 기준이다.
 * @param originLine 그 커밋에서의 줄 번호. 줄이 옮겨졌으면 [line] 과 다르다.
 */
data class BlameLine(
    val line: Int,
    val originLine: Int,
    val commit: CommitId,
    val author: Person,
    val content: String,
)

/**
 * blame 결과.
 *
 * **이진 파일과 빈 파일은 실패가 아니다.** 예외로 올리면 화면이 "왜 안 되는지" 를 안내할 수 없다 —
 * 이진 파일은 줄 개념이 없어 지원하지 않고, 빈 파일은 결과가 비어 있는 것이 맞다.
 */
sealed interface BlameResult {

    /** 계산된 줄들. 요청한 범위 밖은 담기지 않는다. */
    data class Lines(val lines: List<BlameLine>) : BlameResult

    /** 줄 개념이 없어 blame 을 계산하지 않았다. */
    data object Unsupported : BlameResult
}

/**
 * blame 을 계산할 줄 범위. 1부터 세고 [end] 를 포함한다.
 *
 * 범위를 필수로 받는 이유는 blame 이 비싸기 때문이다 — 큰 파일을 한 번에 계산하면 초 단위로 멈춘다.
 * 화면은 보이는 구간만 먼저 요청하고 스크롤에 따라 넓힌다. 전체가 필요하면 [whole] 을 쓴다.
 */
class LineRange private constructor(val start: Int, val end: Int) {

    /** 전체를 요청한 범위인지 — 구현이 상한을 파일 길이로 잡는다. */
    val isWhole: Boolean get() = this == whole()

    override fun equals(other: Any?): Boolean =
        other is LineRange && other.start == start && other.end == end

    override fun hashCode(): Int = start * HASH_FACTOR + end

    override fun toString(): String = "LineRange($start..$end)"

    companion object {
        private const val HASH_FACTOR = 31
        private const val UNBOUNDED = Int.MAX_VALUE

        /**
         * @throws UndineException.StateViolation [start] 가 1보다 작거나 [end] 가 [start] 보다 작을 때
         */
        fun of(start: Int, end: Int): LineRange {
            if (start < 1 || end < start) {
                throw UndineException.StateViolation("잘못된 줄 범위입니다: $start..$end")
            }
            return LineRange(start, end)
        }

        /** 파일 전체. 큰 파일에서는 느리다는 것을 호출부가 알고 쓰는 값이다. */
        fun whole(): LineRange = LineRange(1, UNBOUNDED)
    }
}

/**
 * blame·파일 이력 조회 계약. 구현은 `BlameGatewayImpl` 이다.
 *
 * **코드 이동·복사 탐지(`-M`/`-C` 상당)는 범위 밖이다** — 파일 단위 rename 추적과는 다른 기능이고,
 * 사용자 옵션으로도 노출하지 않는다. 필요해지면 엔진 선택을 정하는 후속 티켓이 다룬다.
 */
interface BlameGateway {

    /**
     * [path] 의 [range] 구간을 blame 한다.
     *
     * @param ignoreWhitespace true 면 들여쓰기만 바꾼 커밋이 결과를 덮지 않는다. 그것을 무시하지
     *   않으면 포맷 커밋 하나가 파일 전체의 실제 작성자를 가린다.
     * @param at 기준 커밋. null 이면 현재 HEAD 다. 삭제된 파일은 그 파일이 있던 커밋을 지정한다.
     * @throws UndineException.NotFound 그 커밋에 그 경로가 없을 때
     */
    suspend fun blame(
        path: String,
        range: LineRange,
        ignoreWhitespace: Boolean,
        at: CommitId? = null,
    ): BlameResult

    /**
     * [path] 를 건드린 커밋을 최신부터 준다. **이름 변경을 따라간다** — rename 지점에서 끊기면 파일의
     * 진짜 시작점을 볼 수 없다.
     *
     * @param at 기준 커밋. null 이면 현재 HEAD 다. 삭제된 파일도 그 파일이 있던 커밋을 주면 조회된다.
     * @throws UndineException.NotFound 그 커밋에서 그 경로의 이력을 찾을 수 없을 때
     */
    suspend fun fileHistory(path: String, at: CommitId? = null, limit: Int): List<Commit>
}
