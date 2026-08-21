package dev.undine.infrastructure.git.diff

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryHolder
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** 커밋 타임스탬프는 고정값을 쓴다 — 시간에 의존하는 테스트를 만들지 않는다. */
internal val TEST_IDENTITY = PersonIdent(
    "Undine Tester",
    "tester@undine.dev",
    Instant.ofEpochMilli(1_700_000_000_000L),
    ZoneOffset.UTC,
)

internal fun initRepository(directory: File): Git {
    directory.mkdirs()
    val git = Git.init().setDirectory(directory).call()
    git.repository.config.apply {
        setString("user", null, "name", TEST_IDENTITY.name)
        setString("user", null, "email", TEST_IDENTITY.emailAddress)
        save()
    }
    return git
}

internal fun Git.writeFile(path: String, content: String) {
    val file = File(repository.workTree, path)
    file.parentFile?.mkdirs()
    file.writeText(content)
}

internal fun Git.writeBytes(path: String, content: ByteArray) {
    File(repository.workTree, path).writeBytes(content)
}

/** 신규·수정·삭제를 모두 인덱스에 올린 뒤 커밋한다. */
internal fun Git.stageAll() {
    add().addFilepattern(".").call()
    add().setUpdate(true).addFilepattern(".").call()
}

internal fun Git.commitAll(message: String): RevCommit {
    stageAll()
    return commit().setMessage(message).setAuthor(TEST_IDENTITY).setCommitter(TEST_IDENTITY).call()
}

internal fun RevCommit.id(): CommitId = CommitId.of(name)

/**
 * 테스트가 이미 열어 둔 핸들을 홀더에 그대로 물린 [GitAccess].
 *
 * 게이트웨이는 이 경계를 통해서만 저장소를 만지므로 (wave 2 정정 C1) 테스트도 같은 경계를 쓴다.
 * 같은 저장소를 두 번 열지 않아 핸들 수명이 테스트의 `Git` 하나로 남는다.
 */
internal suspend fun Git.sharedAccess(): GitAccess =
    GitAccess(RepositoryHolder { repository }).also { access ->
        access.open(RepositoryPath(repository.workTree.path)) { }
    }

internal suspend fun Git.diffGateway(): DiffGatewayImpl = DiffGatewayImpl(sharedAccess())
