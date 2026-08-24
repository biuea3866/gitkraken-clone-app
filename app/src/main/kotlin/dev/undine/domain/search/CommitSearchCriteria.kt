package dev.undine.domain.search

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.FileChange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 커밋 검색 조건 — 티켓 다이어그램의 `CommitPredicate` 에 해당하는 **순수 판정 규칙**이다.
 *
 * 조건은 전부 **AND** 로 결합한다 (wave 3 결정 §UND-20). 비어 있는 축은 조건이 없는 것으로 보고
 * 통과시킨다. 저장소를 읽지 않으므로 화면 없이 단위 테스트로 규칙을 고정할 수 있다.
 *
 * 판정은 두 단계로 나뉜다 — 커밋 메타데이터만 보는 [matchesMetadata] 와, 커밋마다 diff 계산이 필요해
 * 가장 비싼 [matchesChangedFiles] 다. 호출부는 메타데이터로 후보를 좁힌 뒤에만 변경 파일을 읽는다.
 *
 * @property message 커밋 메시지 부분 일치 (대소문자 무시)
 * @property author 작성자 이름·이메일 부분 일치 (대소문자 무시)
 * @property hashPrefix 커밋 해시 접두사. **짧은 해시를 그대로 받는다** — 40자가 아닌 값을
 *   [CommitId] 로 만들면 실패하므로 비교는 [CommitId.value] 문자열로 한다.
 * @property filePath 변경 파일 경로 **부분 일치 (대소문자 구분)** (wave 3 결정 §UND-20).
 *   경로는 대소문자를 구분하는 파일 시스템에서 서로 다른 파일이다.
 * @property since 시작일. 그날 커밋을 **포함**한다.
 * @property until 종료일. 그날 커밋을 **포함**한다.
 * @property zone [since]·[until] 을 커밋 시각과 견줄 때 쓰는 표준시. 기간 기준 시각은
 *   그래프 정렬과 같은 축인 [Commit.committedAt] 이다 (wave 3 결정 §UND-20).
 */
data class CommitSearchCriteria(
    val message: String = "",
    val author: String = "",
    val hashPrefix: String = "",
    val filePath: String = "",
    val since: LocalDate? = null,
    val until: LocalDate? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** 조건이 하나도 없다. 이 상태에서는 검색을 시작하지 않는다 (wave 3 결정 §UND-20). */
    val isEmpty: Boolean
        get() = message.isBlank() &&
            author.isBlank() &&
            hashPrefix.isBlank() &&
            filePath.isBlank() &&
            since == null &&
            until == null

    /** 변경 파일을 읽어야만 판정할 수 있는 조건이 있다 — 가장 비싼 축이다. */
    val requiresFileChanges: Boolean get() = filePath.isNotBlank()

    /** 저장소를 읽지 않고 판정 가능한 축(메시지·작성자·해시·기간)의 결합 결과. */
    fun matchesMetadata(commit: Commit): Boolean =
        matchesMessage(commit.message) &&
            matchesAuthor(commit) &&
            matchesHash(commit.id) &&
            matchesRange(commit.committedAt)

    /** 경로 조건이 없으면 변경 파일을 보지 않고 통과시킨다. */
    fun matchesChangedFiles(changes: List<FileChange>): Boolean =
        !requiresFileChanges || changes.any { change -> change.path.contains(filePath) }

    private fun matchesMessage(commitMessage: String): Boolean =
        message.isBlank() || commitMessage.contains(message, ignoreCase = true)

    private fun matchesAuthor(commit: Commit): Boolean =
        author.isBlank() ||
            commit.author.name.contains(author, ignoreCase = true) ||
            commit.author.email.contains(author, ignoreCase = true)

    private fun matchesHash(id: CommitId): Boolean =
        hashPrefix.isBlank() || id.value.startsWith(hashPrefix.trim().lowercase())

    private fun matchesRange(committedAt: Instant): Boolean {
        if (since == null && until == null) return true
        val committedOn = committedAt.atZone(zone).toLocalDate()
        return (since == null || !committedOn.isBefore(since)) &&
            (until == null || !committedOn.isAfter(until))
    }
}
