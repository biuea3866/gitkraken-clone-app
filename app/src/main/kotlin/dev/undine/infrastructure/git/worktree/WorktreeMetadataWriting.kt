package dev.undine.infrastructure.git.worktree

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Comparator.reverseOrder

/**
 * `.git/worktrees` 저수준 메타데이터를 **쓰는** 쪽. 읽기·해석은 [worktreeListing] 이 있는
 * `WorktreeMetadata.kt` 가 맡는다.
 *
 * 이 파일의 함수들은 두 규칙을 지킨다 — 배치는 임시 위치에서 완성한 뒤 rename 해서 중간 상태를
 * 남기지 않고, 정리는 **자기가 만든 것만** 되돌린다. 검사 시점과 정리 시점 사이에 사용자가 넣은
 * 파일을 함께 지우지 않도록, 지울 대상은 전부 생성·검사 시점에 기록해 둔다.
 */

/**
 * 파일 시스템 rename 경계. 기본은 [ATOMIC_FILE_MOVE] 이며, 테스트는 원자적 rename 을 지원하지
 * 않는 파일 시스템을 이 자리에 주입해 두 확정 지점을 각각 실패시킨다. 미지원 예외의 번역은
 * 주입 대상 밖([moveAtomically])에 남는다.
 */
internal typealias FileMove = (source: Path, destination: Path) -> Unit

internal val ATOMIC_FILE_MOVE: FileMove = { source, destination -> Files.move(source, destination, ATOMIC_MOVE) }

/**
 * 등록 디렉터리와 연결 worktree 의 `.git` 파일을 **중간 상태를 노출하지 않고** 제자리에 놓는다.
 *
 * 순서 자체가 프로토콜이다 — `.git` 파일을 먼저 원자적으로 놓고, 등록 디렉터리의 rename 을
 * **유일한 확정 지점**으로 삼는다. 목록은 등록만 훑으므로 등록이 보이는 순간에는 `.git` 파일이
 * 이미 완성돼 있고, 그 전에 실패하면 등록은 아예 나타나지 않는다.
 * 반대로 놓으면 "등록만 있고 `.git` 은 없는" 중간 상태가 목록에 잡힌다.
 *
 * 실패하면 **이 함수가 만든 것만** 되돌린다 — 임시 등록 디렉터리와, 이미 놓았다면 `.git` 파일.
 * 대상 디렉터리의 다른 파일은 손대지 않는다.
 */
internal fun placeWorktreeMetadata(
    registration: Path,
    gitFile: Path,
    branch: String,
    fileMove: FileMove = ATOMIC_FILE_MOVE,
) {
    val parent = registration.parent
    Files.createDirectories(parent)
    val temporaryRegistration = Files.createTempDirectory(parent, ".${registration.fileName}-")
    var gitFilePlaced = false
    try {
        Files.writeString(temporaryRegistration.resolve(GITDIR_FILE_NAME), "${gitFile.toFile().canonicalPath}\n")
        Files.writeString(temporaryRegistration.resolve(COMMONDIR_FILE_NAME), COMMONDIR_CONTENT)
        Files.writeString(temporaryRegistration.resolve(Constants.HEAD), "$HEAD_REF_PREFIX$branch\n")
        writeAtomically(gitFile, "$GITDIR_PREFIX${registration.toFile().canonicalPath}\n", fileMove)
        gitFilePlaced = true
        moveAtomically(temporaryRegistration, registration, fileMove)
    } catch (failure: IOException) {
        val cleanupFailures = mutableListOf<IOException>()
        cleanupFailures.collectFailure {
            if (Files.exists(temporaryRegistration)) deleteDirectory(temporaryRegistration)
        }
        if (gitFilePlaced) cleanupFailures.collectFailure { Files.deleteIfExists(gitFile) }
        cleanupFailures.forEach(failure::addSuppressed)
        throw failure
    }
}

/**
 * 연결 worktree 에 [branch] 를 체크아웃한다.
 *
 * **강제하지 않는다.** 대상 디렉터리가 비었는지 확인한 뒤에도 검사와 체크아웃 사이에 파일이
 * 생길 수 있고, 강제 체크아웃은 그 파일을 말없이 덮어쓴다. 충돌은 실패로 돌려 호출자가
 * 정리하게 한다 — 사용자 파일을 잃는 것보다 생성이 실패하는 편이 낫다.
 */
internal fun checkoutLinkedWorktree(gitFile: File, branch: String) {
    openLinkedRepository(gitFile).use { linkedRepository ->
        Git.wrap(linkedRepository).use { git -> git.checkout().setName(branch).call() }
    }
}

/**
 * [target] 까지 없는 디렉터리를 만들고 **실제로 만든 경로만** 깊은 것부터 돌려준다.
 *
 * 이 기록이 실패 시 정리의 유일한 근거다. "만들었을 것으로 기대한" 디렉터리를 재귀 삭제하면
 * 그 사이 사용자가 넣은 파일이 함께 사라지므로, 만든 사실을 **생성 시점에** 남긴다.
 */
internal fun createMissingDirectories(target: Path): List<Path> {
    val missing = generateSequence(target) { it.parent }.takeWhile { !Files.exists(it) }.toList()
    Files.createDirectories(target)
    return missing
}

/**
 * 연결 worktree 의 디렉터리와 등록을 지운다. **단계마다 남는 상태를 고정해** 복구 가능성을 보장한다.
 *
 * [recordedContents] 는 더티 검사 시점의 내용이다. 그 뒤에 생긴 파일이 하나라도 있으면
 * **아무것도 지우지 않고** 중단한다 — 검사에 걸리지 않은 사용자 파일을 지우느니 제거가 실패하는
 * 편이 낫고, `.git` 과 등록이 그대로라 그대로 다시 부를 수 있다.
 *
 * `.git` 파일이 살아 있는 동안의 실패는 등록도 그대로라 목록에 정상으로 잡히고 **제거를 다시
 * 부를 수 있다**. `.git` 을 지운 뒤라면 되돌아갈 수 없으므로 등록까지 끝내 지운다 — 그러지 않으면
 * "읽을 수 없는 등록" 으로 굳어 제거도 재시도도 불가능해진다. 남은 디렉터리는 실패로 알린다.
 */
internal fun removeLinkedWorktree(worktreeDirectory: Path, registration: Path, recordedContents: Set<Path>) {
    val gitFile = worktreeDirectory.resolve(GIT_FILE_NAME)
    requireNoUnrecordedContents(worktreeDirectory, recordedContents)
    // 기록된 항목만, 깊은 경로부터 지운다. `.git` 은 되돌릴 수 없는 마지막 단계라 남겨 둔다.
    recordedContents.asSequence()
        .filter { it != worktreeDirectory && it != gitFile }
        .sortedWith(reverseOrder())
        .forEach(Files::deleteIfExists)
    Files.deleteIfExists(gitFile)
    val leftover = try {
        Files.deleteIfExists(worktreeDirectory)
        null
    } catch (failure: IOException) {
        failure
    }
    deleteDirectory(registration)
    if (leftover != null) {
        throw IOException("등록은 지웠지만 worktree 디렉터리 '$worktreeDirectory' 가 남았습니다", leftover)
    }
}

/**
 * 실패한 생성을 **자기가 만든 것만** 되돌린다.
 *
 * 대상 디렉터리를 재귀 삭제하지 않는다 — 비었는지 확인한 시점과 정리 시점 사이에 생긴 파일이
 * 함께 사라진다. [createdDirectories] 는 만든 디렉터리 기록이며, 그 사이 내용이 생겼다면
 * 디렉터리는 남고 그 사실이 실패 목록으로 보고된다.
 */
internal fun cleanupFailedAdd(
    createdDirectories: List<Path>,
    gitFile: Path,
    registration: Path,
): List<IOException> {
    val failures = mutableListOf<IOException>()
    // 등록을 먼저 지운다 — 목록에 잡히는 것은 등록뿐이라, 이것이 사라지면 실패한 생성이 보이지 않는다.
    // 등록 디렉터리는 통째로 이 앱이 만든 것이라 재귀 삭제해도 사용자 파일이 걸리지 않는다.
    failures.collectFailure { if (Files.exists(registration)) deleteDirectory(registration) }
    failures.collectFailure { Files.deleteIfExists(gitFile) }
    // 깊은 것부터, **비어 있을 때만** 지운다. 그 사이 파일이 생겼으면 남기고 실패로 보고한다.
    createdDirectories.forEach { directory -> failures.collectFailure { Files.deleteIfExists(directory) } }
    return failures
}

internal fun deleteDirectory(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { paths -> paths.sorted(reverseOrder()).forEach(Files::delete) }
}

private fun requireNoUnrecordedContents(worktreeDirectory: Path, recordedContents: Set<Path>) {
    val unrecorded = worktreeContents(worktreeDirectory).filterNot(recordedContents::contains).sorted()
    if (unrecorded.isEmpty()) return
    throw IOException(
        "더티 검사 이후 생긴 파일이 있어 worktree '$worktreeDirectory' 제거를 중단했습니다: " +
            unrecorded.joinToString { it.toString() },
    )
}

private inline fun MutableList<IOException>.collectFailure(block: () -> Unit) {
    try {
        block()
    } catch (failure: IOException) {
        this += failure
    }
}

private fun writeAtomically(destination: Path, content: String, fileMove: FileMove) {
    val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}-", ".tmp")
    try {
        Files.writeString(temporary, content)
        moveAtomically(temporary, destination, fileMove)
    } catch (failure: IOException) {
        Files.deleteIfExists(temporary)
        throw failure
    }
}

private fun moveAtomically(source: Path, destination: Path, fileMove: FileMove) {
    try {
        fileMove(source, destination)
    } catch (failure: AtomicMoveNotSupportedException) {
        throw IOException("worktree 메타데이터의 원자적 rename을 지원하지 않는 파일 시스템입니다", failure)
    }
}
