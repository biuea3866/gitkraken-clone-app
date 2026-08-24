package dev.undine.domain.conflict

/** 충돌 표식. git 이 워킹트리 파일에 쓰는 그 문자열이다 — 우리가 정하는 값이 아니다. */
internal const val OURS_MARKER = "<<<<<<<"
internal const val BASE_MARKER = "|||||||"
internal const val SEPARATOR_MARKER = "======="
internal const val THEIRS_MARKER = ">>>>>>>"

/** 충돌 구간에서 어느 쪽을 채택할지. */
enum class ConflictSide {
    OURS,
    THEIRS,
}

/** 한 충돌 구간의 해결 방식. */
sealed interface ConflictChoice {

    /** 아직 고르지 않았다. 이 구간이 남아 있으면 완료를 막는다. */
    data object Unresolved : ConflictChoice

    /** 한쪽을 채택한다. */
    data class Take(val side: ConflictSide) : ConflictChoice

    /** 양쪽을 순서대로 넣는다 — ours 다음 theirs. */
    data object TakeBoth : ConflictChoice

    /** 사용자가 직접 쓴 내용으로 대체한다. */
    data class Edited(val lines: List<String>) : ConflictChoice
}

/**
 * 문서를 이루는 조각. 충돌이 아닌 부분은 그대로 남고, 충돌 구간만 선택에 따라 바뀐다.
 *
 * sealed 인 이유: "충돌인가 아닌가" 를 nullable 로 표현하면 렌더링이 그 분기를 조용히 빠뜨린다.
 */
sealed interface ConflictSegment {

    /** 충돌이 아닌 구간. 그대로 결과에 들어간다. */
    data class Stable(val lines: List<String>) : ConflictSegment

    /**
     * 충돌 구간.
     *
     * @param base `diff3` 스타일 표식이 있을 때만 채워진다. 일반 표식에는 base 가 없어 빈 목록이다.
     */
    data class Conflict(
        val ours: List<String>,
        val base: List<String>,
        val theirs: List<String>,
        val choice: ConflictChoice = ConflictChoice.Unresolved,
    ) : ConflictSegment
}

/**
 * 충돌 표식이 든 파일 하나를 구간으로 쪼갠 문서.
 *
 * **세 버전을 따로 읽지 않고 표식이 든 워킹트리 파일을 파싱한다.** 인덱스의 stage 1·2·3 을 각각
 * 읽어 3-way 로 재구성할 수도 있지만, 그러면 사용자가 이미 손으로 고친 내용이 사라진다 —
 * 화면이 보여줘야 하는 것은 "지금 파일에 무엇이 있는가" 다.
 *
 * 표식이 열린 채 끝나는(닫는 표식이 없는) 파일도 파싱한다 — 그 상태로도 사용자는 고쳐야 하고,
 * 파싱을 거부하면 화면이 아무것도 못 보여준다. 남은 표식은 [unresolvedLineNumbers] 가 잡는다.
 */
class ConflictDocument private constructor(val segments: List<ConflictSegment>) {

    /** 충돌 구간 수. 진행률의 분모다. */
    val conflictCount: Int get() = segments.count { it is ConflictSegment.Conflict }

    /** 아직 고르지 않은 구간 수. 0 이면 저장할 수 있다. */
    val unresolvedCount: Int
        get() = segments.count { it is ConflictSegment.Conflict && it.choice is ConflictChoice.Unresolved }

    val isResolved: Boolean get() = unresolvedCount == 0

    /** [index] 번째 충돌 구간의 선택을 바꾼 새 문서. 원본은 그대로다 (불변). */
    fun choose(index: Int, choice: ConflictChoice): ConflictDocument {
        var conflictIndex = -1
        return ConflictDocument(
            segments.map { segment ->
                when (segment) {
                    is ConflictSegment.Stable -> segment
                    is ConflictSegment.Conflict -> {
                        conflictIndex++
                        if (conflictIndex == index) segment.copy(choice = choice) else segment
                    }
                }
            },
        )
    }

    /**
     * 선택을 반영한 결과 텍스트.
     *
     * 아직 고르지 않은 구간은 **표식을 그대로 남긴다** — 임의로 한쪽을 고르면 사용자가 보지 않은
     * 내용이 저장된다. 그 표식은 [unresolvedLineNumbers] 가 잡아 저장을 막는다.
     */
    fun render(): String = buildList {
        segments.forEach { segment ->
            when (segment) {
                is ConflictSegment.Stable -> addAll(segment.lines)
                is ConflictSegment.Conflict -> addAll(segment.renderLines())
            }
        }
    }.joinToString("\n")

    /**
     * 결과에 남아 있는 충돌 표식의 줄 번호(1부터).
     *
     * 표식이 남은 채로 스테이징되면 그대로 커밋되어 소스에 표식이 박힌다 — 저장 전에 여기서 잡는다.
     * 사용자가 직접 편집해 표식을 다시 써 넣은 경우도 걸린다.
     */
    fun unresolvedLineNumbers(): List<Int> =
        render().lineSequence()
            .withIndex()
            .filter { (_, line) -> line.startsWithAnyMarker() }
            .map { (index, _) -> index + 1 }
            .toList()

    companion object {

        fun parse(content: String): ConflictDocument = ConflictDocument(parseSegments(content.lines()))
    }
}

private fun ConflictSegment.Conflict.renderLines(): List<String> = when (val current = choice) {
    ConflictChoice.Unresolved -> markerLines()
    is ConflictChoice.Take -> if (current.side == ConflictSide.OURS) ours else theirs
    ConflictChoice.TakeBoth -> ours + theirs
    is ConflictChoice.Edited -> current.lines
}

/** 고르지 않은 구간을 원래 표식 모양으로 되돌린다 — base 가 있었으면 diff3 모양을 유지한다. */
private fun ConflictSegment.Conflict.markerLines(): List<String> = buildList {
    add(OURS_MARKER)
    addAll(ours)
    if (base.isNotEmpty()) {
        add(BASE_MARKER)
        addAll(base)
    }
    add(SEPARATOR_MARKER)
    addAll(theirs)
    add(THEIRS_MARKER)
}

private fun String.startsWithAnyMarker(): Boolean =
    startsWith(OURS_MARKER) || startsWith(SEPARATOR_MARKER) ||
        startsWith(THEIRS_MARKER) || startsWith(BASE_MARKER)

/**
 * 표식을 기준으로 줄을 구간으로 나눈다.
 *
 * 상태 기계로 읽는다 — 표식이 중첩되지 않는다는 git 의 보장을 전제한다. 예상 밖 순서(닫는 표식 없이
 * 파일이 끝남)는 그때까지 모은 내용을 충돌 구간으로 확정해 **버리지 않는다.**
 */
private fun parseSegments(lines: List<String>): List<ConflictSegment> {
    val reader = SegmentReader()
    lines.forEach(reader::read)
    return reader.finish()
}

/**
 * 파싱 중 상태를 담는다. 분기를 이 클래스의 메서드로 나눠 두는 이유는 한 함수에 몰면 표식 종류만큼
 * 분기가 쌓여 읽기 어려워지기 때문이다.
 */
private class SegmentReader {

    private val segments = mutableListOf<ConflictSegment>()
    private val stable = mutableListOf<String>()
    private var ours: MutableList<String>? = null
    private var base: MutableList<String>? = null
    private var theirs: MutableList<String>? = null

    fun read(line: String) {
        if (readMarker(line)) return
        collect(line)
    }

    fun finish(): List<ConflictSegment> {
        // 닫는 표식 없이 끝난 충돌도 구간으로 확정한다 — 모은 내용을 버리면 화면이 보여줄 것이 없다.
        if (ours != null) flushConflict() else flushStable()
        return segments.toList()
    }

    /** 표식 줄이면 상태를 옮기고 true. 충돌 밖에서 만난 표식 모양 줄은 본문으로 본다. */
    private fun readMarker(line: String): Boolean = when {
        line.startsWith(OURS_MARKER) -> {
            flushStable()
            ours = mutableListOf()
            base = null
            theirs = null
            true
        }

        ours == null -> false
        line.startsWith(BASE_MARKER) -> {
            base = mutableListOf()
            true
        }

        line.startsWith(SEPARATOR_MARKER) -> {
            theirs = mutableListOf()
            true
        }

        line.startsWith(THEIRS_MARKER) -> {
            flushConflict()
            true
        }

        else -> false
    }

    /** 지금 열려 있는 가장 안쪽 버퍼에 담는다. 충돌 밖이면 안정 구간이다. */
    private fun collect(line: String) {
        val target = theirs ?: base ?: ours
        if (target == null) stable += line else target += line
    }

    private fun flushStable() {
        if (stable.isEmpty()) return
        segments += ConflictSegment.Stable(stable.toList())
        stable.clear()
    }

    private fun flushConflict() {
        segments += ConflictSegment.Conflict(
            ours = ours?.toList().orEmpty(),
            base = base?.toList().orEmpty(),
            theirs = theirs?.toList().orEmpty(),
        )
        ours = null
        base = null
        theirs = null
    }
}
