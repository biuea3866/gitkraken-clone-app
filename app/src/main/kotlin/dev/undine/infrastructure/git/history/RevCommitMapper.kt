package dev.undine.infrastructure.git.history

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit

/**
 * JGit `RevCommit` 을 도메인 [Commit] 으로 옮긴다.
 *
 * 부모는 **전부** 보존한다 — 첫 부모만 남기면 UND-04 가 병합 레인을 이을 수 없다.
 * 변환만 하고 도메인 규칙은 담지 않는다.
 */
internal fun RevCommit.toCommit(): Commit = Commit(
    id = CommitId.of(name),
    parents = parents.map { parent -> CommitId.of(parent.name) },
    message = fullMessage,
    author = authorIdent.toPerson(),
    committer = committerIdent.toPerson(),
    authoredAt = authorIdent.whenAsInstant,
    committedAt = committerIdent.whenAsInstant,
)

private fun PersonIdent.toPerson(): Person = Person(name = name, email = emailAddress)
