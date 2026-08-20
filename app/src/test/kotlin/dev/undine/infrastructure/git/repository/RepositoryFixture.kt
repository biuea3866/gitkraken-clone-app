package dev.undine.infrastructure.git.repository

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset

/**
 * Gateway 통합 테스트가 쓰는 **실제 저장소** fixture. JGit 을 Mock 으로 대체하지 않는다.
 *
 * 초기 브랜치와 커밋 시각을 고정한다 — 사용자의 전역 `init.defaultBranch` 나 실행 시각에
 * 따라 결과가 흔들리면 테스트가 저장소 동작을 검증하지 못한다.
 */
internal const val INITIAL_BRANCH = "main"

/** 권한 테스트가 `tempdir()` 정리를 막지 않도록 되돌릴 때 쓰는 원래 권한. */
internal val READ_WRITE_EXECUTE_OWNER: Set<PosixFilePermission> =
    PosixFilePermissions.fromString("rwx------")

private val FIXED_AUTHOR = PersonIdent(
    "Undine Test",
    "test@undine.dev",
    Instant.parse("2026-01-02T03:04:05Z"),
    ZoneOffset.UTC,
)

internal fun initRepository(directory: File): Git =
    Git.init()
        .setDirectory(directory)
        .setInitialBranch(INITIAL_BRANCH)
        .call()

internal fun initBareRepository(directory: File): Git =
    Git.init()
        .setBare(true)
        .setDirectory(directory)
        .setInitialBranch(INITIAL_BRANCH)
        .call()

/** 워킹트리에 파일을 쓰고 인덱스에 올려 커밋한다. */
internal fun Git.commitFile(name: String, content: String, message: String): RevCommit {
    writeWorkTreeFile(name, content)
    add().addFilepattern(name).call()
    return commit()
        .setMessage(message)
        .setAuthor(FIXED_AUTHOR)
        .setCommitter(FIXED_AUTHOR)
        .call()
}

internal fun Git.writeWorkTreeFile(name: String, content: String) {
    File(repository.workTree, name).writeText(content)
}

internal fun Git.deleteWorkTreeFile(name: String) {
    File(repository.workTree, name).delete()
}

/**
 * `main` 과 `feature` 가 같은 파일을 서로 다르게 바꾼 저장소를 만든다.
 * 병합·리베이스 충돌 fixture 의 공통 전제다. 반환 시점의 체크아웃 브랜치는 `main` 이다.
 */
internal fun Git.createDivergedBranches(fileName: String) {
    commitFile(fileName, "base\n", "base")
    branchCreate().setName("feature").call()
    checkout().setName("feature").call()
    commitFile(fileName, "feature\n", "feature change")
    checkout().setName(INITIAL_BRANCH).call()
    commitFile(fileName, "main\n", "main change")
}
