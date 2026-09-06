package dev.undine.infrastructure.git.patch

import dev.undine.domain.RepositoryPath
import dev.undine.domain.patch.PatchGateway
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal const val MAIN_BRANCH = "main"
internal const val FILE_PATH = "a.txt"
internal const val FIVE_LINES = "l1\nl2\nl3\nl4\nl5\n"
internal const val CONFLICT_MARKER = "<<<<<<<"

internal fun TestConfiguration.initRepository(): Git =
    Git.init().setDirectory(tempdir()).setInitialBranch(MAIN_BRANCH).call().also { git -> git.configureAuthor() }

internal fun Git.configureAuthor(name: String = "Undine Tester", email: String = "tester@undine.dev") {
    repository.config.apply {
        setString("user", null, "name", name)
        setString("user", null, "email", email)
        save()
    }
}

internal fun Git.writeFile(path: String, content: String): File =
    File(repository.workTree, path).apply {
        parentFile.mkdirs()
        writeText(content)
    }

internal fun Git.readFile(path: String): String = File(repository.workTree, path).readText()

/** 새 파일과 삭제를 **둘 다** 담아야 패치 생성용 기준 상태가 실제 변경과 어긋나지 않는다. */
internal fun Git.stageAll() {
    add().addFilepattern(".").call()
    add().addFilepattern(".").setUpdate(true).call()
}

internal fun Git.commitAll(message: String): RevCommit {
    stageAll()
    return commit().setMessage(message).call()
}

internal fun Git.headCommit(): RevCommit = repository.parseCommit(repository.resolve(Constants.HEAD))

/** HEAD 가 담고 있는 [path] 의 blob id — 그 객체 하나만 골라 망가뜨릴 때 쓴다. */
internal fun Git.blobIdOf(path: String): ObjectId =
    repository.resolve("${Constants.HEAD}:$path")

internal fun Git.resetHardTo(commit: ObjectId) {
    reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(commit.name).call()
}

/**
 * 두 커밋 사이의 diff 를 패치 바이트로 만든다.
 *
 * Gateway 를 거치지 않고 JGit 으로 직접 만든다 — 생성이 깨졌을 때 적용 테스트까지 함께 무너지면
 * 어느 쪽 문제인지 알 수 없다.
 */
internal fun Repository.diffBytes(from: RevCommit, to: RevCommit, detectRenames: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    DiffFormatter(out).use { formatter ->
        formatter.setRepository(this)
        formatter.setDetectRenames(detectRenames)
        formatter.format(from.tree, to.tree)
    }
    return out.toByteArray()
}

/**
 * 별도의 임시 저장소에서 [seed] → [change] 변화를 만들고 그 패치를 돌려준다.
 *
 * 대상 저장소가 아니라 **다른 저장소**에서 만드는 것이 요점이다. 같은 저장소에서 만들면 조상 blob 이
 * 항상 존재해 "조상 blob 이 없는 진짜 충돌" 을 재현할 수 없다.
 */
internal fun TestConfiguration.patchBetween(
    seed: (Git) -> Unit,
    change: (Git) -> Unit,
    detectRenames: Boolean = false,
): ByteArray =
    initRepository().use { git ->
        seed(git)
        val base = git.commitAll("base")
        change(git)
        val changed = git.commitAll("changed")
        git.repository.diffBytes(base, changed, detectRenames)
    }

internal suspend fun <T> Git.withPatchGateway(
    verifyPromotion: (Repository, ObjectId, List<String>) -> Unit = ::verifyWorkingTreeMatches,
    block: suspend (PatchGateway) -> T,
): T {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(repository.workTree.path)) { }
    return try {
        block(PatchGatewayImpl(gitAccess, verifyPromotion))
    } finally {
        gitAccess.close()
    }
}

/**
 * 느슨한 객체 파일을 못 읽는 바이트로 덮어쓴다 — **없는 객체가 아니라 못 읽는 객체**를 만든다.
 *
 * 두 상태를 구분하는 것이 요점이다. 파일을 지우면 JGit 이 `MissingObjectException` 을 던져
 * "그런 객체 없음" 이 되지만, 내용이 깨지면 `CorruptObjectException` — 저장소 I/O 실패다.
 * 후자를 정상 결과(충돌·NotFound)로 접지 않는지가 이 픽스처로 검증하려는 것이다.
 */
internal fun Git.corruptLooseObject(id: ObjectId) {
    val name = id.name
    val looseObject = File(repository.directory, "objects/${name.take(2)}/${name.drop(2)}")
    // git 은 느슨한 객체를 읽기 전용으로 쓴다 — 지우고 다시 쓰지 않으면 덮어쓰기가 거부된다.
    check(looseObject.delete()) { "느슨한 객체를 찾지 못했습니다: $looseObject" }
    looseObject.writeBytes("이것은 zlib 로 풀리지 않는다".toByteArray())
}

/** 승격 직후 반드시 실패해 복원 경로를 타게 하는 검증 지점. */
internal val alwaysFailingPromotion: (Repository, ObjectId, List<String>) -> Unit =
    { _, _, _ -> error("승격 후 검증 실패(테스트가 의도한 실패)") }

/** 링크를 따라가지 않고 본다 — 따라가면 심볼릭 링크가 가리키는 바깥 대상을 검사하게 된다. */
internal fun Git.isSymbolicLink(path: String): Boolean =
    Files.isSymbolicLink(File(repository.workTree, path).toPath())

internal fun Git.isExecutable(path: String): Boolean =
    File(repository.workTree, path).canExecute()

internal fun Git.exists(path: String): Boolean =
    Files.exists(File(repository.workTree, path).toPath(), LinkOption.NOFOLLOW_LINKS)

/** HEAD·인덱스·워킹트리 세 축을 함께 본다 — 한 축만 보면 다른 축을 건드리고 실패한 적용을 통과시킨다. */
internal data class RepositorySnapshot(
    val head: String?,
    val indexEntries: Map<String, String>,
    val workingTreeFiles: Map<String, String>,
)

internal fun Git.snapshot(): RepositorySnapshot {
    val cache = repository.readDirCache()
    return RepositorySnapshot(
        head = repository.resolve(Constants.HEAD)?.name,
        indexEntries = (0 until cache.entryCount).associate { index ->
            cache.getEntry(index).let { entry -> entry.pathString to "${entry.fileMode.bits}:${entry.objectId.name}" }
        },
        workingTreeFiles = repository.workTree.walkTopDown()
            .filter { file -> !file.path.contains("/.git/") && file.isFile }
            .associate { file -> file.relativeTo(repository.workTree).path to file.readText() },
    )
}
