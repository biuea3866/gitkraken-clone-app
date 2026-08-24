package dev.undine.domain.search

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import java.time.Instant
import java.time.ZoneId

/** `CommitId` 가 요구하는 해시 길이. */
private const val HEX_LENGTH = 40

/** 검색 테스트가 공유하는 고정값. 시각·해시를 고정해야 결과가 실행 환경에 흔들리지 않는다. */
internal val SEARCH_ZONE: ZoneId = ZoneId.of("UTC")

internal val FIXED_COMMIT_TIME: Instant = Instant.parse("2026-03-10T12:00:00Z")

/** 40자 hexadecimal 을 접두사로부터 만든다 — 짧은 해시 검색의 대상 커밋을 고정하기 위해서다. */
internal fun hashOf(prefix: String): String = prefix.padEnd(HEX_LENGTH, '0')

internal fun commitOf(
    id: String,
    message: String = "기본 커밋",
    authorName: String = "Undine Tester",
    authorEmail: String = "tester@undine.dev",
    committedAt: Instant = FIXED_COMMIT_TIME,
): Commit {
    val author = Person(name = authorName, email = authorEmail)
    return Commit(
        id = CommitId.of(id),
        parents = emptyList(),
        message = message,
        author = author,
        committer = author,
        authoredAt = committedAt,
        committedAt = committedAt,
    )
}
