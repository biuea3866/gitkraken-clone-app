package dev.undine.infrastructure.git.worktree

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.worktree.UnsupportedWorktreeMetadata
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeListing
import dev.undine.domain.worktree.WorktreeState
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal const val GITDIR_PREFIX = "gitdir: "
internal const val HEAD_REF_PREFIX = "ref: "
internal const val COMMONDIR_CONTENT = "../..\n"
const val GIT_FILE_NAME = ".git"
private const val WORKTREES_DIRECTORY = "worktrees"
internal const val GITDIR_FILE_NAME = "gitdir"
internal const val COMMONDIR_FILE_NAME = "commondir"

internal fun Repository.worktreeListing(): WorktreeListing {
    val commonDirectory = commonDirectory.toPath()
    val mainDirectory = commonDirectory.parent
    val mainName = mainDirectory.fileName?.toString() ?: mainDirectory.toString()
    val worktrees = mutableListOf<Worktree>()
    val unsupported = mutableListOf<UnsupportedWorktreeMetadata>()
    when (val head = readHead(commonDirectory.resolve(Constants.HEAD))) {
        is ParsedHead.Unsupported -> unsupported += UnsupportedWorktreeMetadata(mainName, head.detail)
        is ParsedHead.Detached, is ParsedHead.OnBranch -> worktrees += Worktree(
            name = mainName,
            path = RepositoryPath(mainDirectory.toFile().canonicalPath),
            branch = head.branchOrNull(),
            state = WorktreeState.MAIN,
        )
    }

    val registrations = worktreesDirectory()
    if (!Files.exists(registrations)) return WorktreeListing(worktrees, unsupported)

    Files.list(registrations).use { entries ->
        entries.filter(Files::isDirectory).forEach { registration ->
            when (val parsed = parseRegistration(registration, commonDirectory)) {
                is ParsedRegistration.Supported -> worktrees += parsed.worktree
                is ParsedRegistration.Unsupported -> unsupported += UnsupportedWorktreeMetadata(
                    name = registration.fileName.toString(),
                    detail = parsed.detail,
                )
            }
        }
    }
    return WorktreeListing(worktrees, unsupported)
}

internal fun Repository.worktreesDirectory(): Path = commonDirectory.toPath().resolve(WORKTREES_DIRECTORY)

@Suppress("ReturnCount")
private fun parseRegistration(registration: Path, commonDirectory: Path): ParsedRegistration {
    val gitdir = registration.resolve(GITDIR_FILE_NAME)
    val commondir = registration.resolve(COMMONDIR_FILE_NAME)
    val head = registration.resolve(Constants.HEAD)
    if (!Files.isRegularFile(gitdir) || !Files.isRegularFile(commondir) || !Files.isRegularFile(head)) {
        return ParsedRegistration.Unsupported("gitdir, commondir, HEAD 세 파일이 모두 필요합니다")
    }
    if (Files.readString(commondir) != COMMONDIR_CONTENT) {
        return ParsedRegistration.Unsupported("지원하지 않는 commondir 형식입니다")
    }
    val branch = when (val parsedHead = readHead(head)) {
        is ParsedHead.Unsupported -> return ParsedRegistration.Unsupported(parsedHead.detail)
        else -> parsedHead.branchOrNull()
    }
    val gitFile = Path.of(Files.readString(gitdir).trim()).normalize()
    if (!gitFile.isAbsolute) return ParsedRegistration.Unsupported("gitdir는 절대 경로여야 합니다")
    // 파일명까지 확인해야 부모를 worktree 디렉터리로 삼는 해석이 성립한다.
    // 검사하지 않으면 `<어딘가>/notgit` 같은 등록의 부모를 worktree 로 **추측**하게 된다.
    if (gitFile.fileName?.toString() != GIT_FILE_NAME) {
        return ParsedRegistration.Unsupported("gitdir는 연결 worktree의 $GIT_FILE_NAME 파일을 가리켜야 합니다")
    }
    val worktreeDirectory = gitFile.parent
        ?: return ParsedRegistration.Unsupported("gitdir 경로가 올바르지 않습니다")
    if (!Files.exists(worktreeDirectory)) {
        return ParsedRegistration.Supported(
            Worktree(
                name = registration.fileName.toString(),
                path = RepositoryPath(worktreeDirectory.toString()),
                branch = branch,
                state = WorktreeState.ORPHANED,
            ),
        )
    }
    val expectedGitFile = "$GITDIR_PREFIX${registration.toFile().canonicalPath}"
    if (!Files.isRegularFile(gitFile) || Files.readString(gitFile).trim() != expectedGitFile) {
        return ParsedRegistration.Unsupported("연결 worktree의 $GIT_FILE_NAME 파일이 등록을 가리키지 않습니다")
    }
    val resolvedCommonDirectory = registration.resolve(Files.readString(commondir).trim()).normalize()
    if (resolvedCommonDirectory != commonDirectory) {
        return ParsedRegistration.Unsupported("commondir가 현재 저장소를 가리키지 않습니다")
    }
    return ParsedRegistration.Supported(
        Worktree(
            name = registration.fileName.toString(),
            path = RepositoryPath(worktreeDirectory.toFile().canonicalPath),
            branch = branch,
            state = WorktreeState.LINKED,
        ),
    )
}

/**
 * HEAD 파일 한 줄을 **현행 git 표준으로만** 해석한다.
 *
 * 유효한 symbolic ref 도 object id 도 아니면 detached 로 **추측하지 않는다** —
 * 그렇게 뭉개면 형식이 깨진 등록이 "브랜치 없는 정상 worktree" 로 보인다.
 */
private fun readHead(head: Path): ParsedHead {
    val content = Files.readString(head).trim()
    if (content.startsWith(HEAD_REF_PREFIX)) {
        val reference = content.removePrefix(HEAD_REF_PREFIX).trim()
        return if (reference.startsWith(Constants.R_REFS) && Repository.isValidRefName(reference)) {
            ParsedHead.OnBranch(RefName(reference))
        } else {
            ParsedHead.Unsupported("HEAD 의 참조 '$reference' 가 유효한 ref 이름이 아닙니다")
        }
    }
    return if (ObjectId.isId(content)) {
        ParsedHead.Detached
    } else {
        ParsedHead.Unsupported("지원하지 않는 HEAD 형식입니다")
    }
}

internal fun dirtyPaths(gitFile: File): List<String> = openLinkedRepository(gitFile).use { repository ->
    Git.wrap(repository).use { git ->
        git.status().call().let { status ->
            // ignore 된 파일도 더티로 센다. 제거는 디렉터리를 통째로 지우므로, 빠뜨리면
            // 사용자가 일부러 추적하지 않은 파일(로컬 설정·빌드 산출물)이 경고 없이 사라진다.
            (status.uncommittedChanges + status.untracked + status.conflicting + status.ignoredNotInIndex)
                .distinct()
                .sorted()
        }
    }
}

/**
 * [worktreeDirectory] 의 현재 내용을 기록한다. 더티 검사와 실제 삭제 사이에 생긴 파일을 가려내기
 * 위한 **복구 경계**이며, 기록에 없는 항목은 제거([removeLinkedWorktree])가 손대지 않는다.
 */
internal fun worktreeContents(worktreeDirectory: Path): Set<Path> =
    if (!Files.exists(worktreeDirectory)) {
        emptySet()
    } else {
        Files.walk(worktreeDirectory).use { paths -> paths.toList().toSet() }
    }

internal fun openLinkedRepository(gitFile: File): Repository {
    val builder = FileRepositoryBuilder().setMustExist(true)
    builder.findGitDir(gitFile.parentFile)
    return builder.build()
}

internal fun samePath(left: Path, right: Path): Boolean = left.toFile().canonicalFile == right.toFile().canonicalFile

private sealed interface ParsedHead {

    /** `ref: refs/...` — 유효한 symbolic ref 다. */
    data class OnBranch(val branch: RefName) : ParsedHead

    /** object id 한 줄 — detached HEAD 다. 브랜치가 없는 것이지 형식이 깨진 것이 아니다. */
    data object Detached : ParsedHead

    /** 이 앱이 아는 표준 형식이 아니다. 추측하지 않고 미지원으로 올린다. */
    data class Unsupported(val detail: String) : ParsedHead
}

private fun ParsedHead.branchOrNull(): RefName? = (this as? ParsedHead.OnBranch)?.branch

private sealed interface ParsedRegistration {
    data class Supported(val worktree: Worktree) : ParsedRegistration

    data class Unsupported(val detail: String) : ParsedRegistration
}
