package dev.undine.presentation.palette

import androidx.compose.runtime.Immutable

/** 최근 실행 이력이 없는 명령의 정렬 순위 — 이력이 있는 명령보다 항상 뒤로 간다. */
private const val NEVER_EXECUTED_RANK = Int.MAX_VALUE

private val WORD_SEPARATOR = Regex("\\s+")

/** 팔레트가 한 행에 그리는 데 필요한 것 전부 — 명령, 지금의 실행 조건, OS 표기 단축키. */
@Immutable
class CommandCandidate(
    val command: Command,
    val availability: CommandAvailability,
    val shortcutLabel: String?,
)

/**
 * 검색어에 맞는 명령을 **최근 실행 순 → 등록 순**으로 정렬해 돌려준다.
 *
 * 검색은 표시명의 부분 일치와 약어 일치를 함께 본다 — `cb` 로 `Create Branch` 를 찾을 수 있다.
 * 최근 실행 이력이 없어 동점이면 등록 순서를 유지한다 (같은 검색어에 결과 순서가 흔들리지 않는다).
 *
 * @param recentCommandIds 최신순 실행 이력. 앱 세션 범위이며 영속화하지 않는다 (wave 3 결정 §UND-22).
 */
fun searchCommands(
    commands: List<Command>,
    query: String,
    recentCommandIds: List<CommandId> = emptyList(),
): List<Command> {
    val trimmedQuery = query.trim()
    return commands.withIndex()
        .filter { (_, command) -> command.title.matchesCommandQuery(trimmedQuery) }
        .sortedWith(
            compareBy(
                { recentCommandIds.recencyRank(it.value.id) },
                IndexedValue<Command>::index,
            ),
        )
        .map(IndexedValue<Command>::value)
}

/** 부분 일치(대소문자 무시) 또는 머리글자 약어 일치. 빈 검색어는 모두 통과다. */
internal fun String.matchesCommandQuery(query: String): Boolean = when {
    query.isEmpty() -> true
    contains(query, ignoreCase = true) -> true
    else -> initials().containsInOrder(query)
}

private fun String.initials(): String =
    trim().split(WORD_SEPARATOR)
        .filter(String::isNotBlank)
        .map(String::first)
        .joinToString(separator = "")

/** `CRB` 가 `cb` 를 순서대로 담고 있는지 — 중간 단어를 건너뛰어도 순서만 맞으면 일치다. */
private fun String.containsInOrder(query: String): Boolean {
    var matched = 0
    forEach { character ->
        if (matched < query.length && character.equals(query[matched], ignoreCase = true)) matched++
    }
    return matched == query.length
}

private fun List<CommandId>.recencyRank(id: CommandId): Int =
    indexOf(id).takeIf { it >= 0 } ?: NEVER_EXECUTED_RANK
