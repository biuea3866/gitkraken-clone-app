package dev.undine.infrastructure.git.submodule

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.submodule.SubmoduleWalk
import java.io.File

/**
 * 저장소가 **되돌려줄 수 없는** 것의 종류. 사유마다 사용자가 해야 할 일이 다르므로 하나로 접지 않는다
 * — 커밋하면 되는 것과, 옮겨 두어야 하는 것과, 왜 판정이 안 되는지 봐야 하는 것은 서로 다른 행동이다.
 */
internal enum class PreservationReason(val label: String) {

    /** 커밋되지 않은 변경·충돌. 커밋하거나 되돌리면 저장소가 되찾아 줄 수 있다. */
    UNCOMMITTED("커밋되지 않은 변경"),

    /** 인덱스에 없는 파일. 저장소 어디에도 사본이 없다. */
    UNTRACKED("추적되지 않은 파일"),

    /**
     * `.gitignore` 가 가린 파일. `Status.isClean` 이 참이어도 **사용자 데이터**다 —
     * 빌드 산출물만 있다는 보장이 없고, 지우면 되돌릴 수 없다.
     */
    IGNORED("무시된 파일"),

    /**
     * 부모 gitlink 와 다른 HEAD. 파일은 깨끗하지만 부모가 기록한 커밋으로는 지금 체크아웃된 자리로
     * 돌아올 수 없다 — 서브모듈 안에서만 진전한 작업이다.
     */
    DIVERGED("기록된 커밋과 다른 HEAD"),

    /** 기록된 커밋에서 도달할 수 없는 ref. 지우면 그 커밋에 닿을 길이 사라진다. */
    LOCAL_COMMIT("되살릴 수 없는 로컬 커밋"),

    /** stash 엔트리. 어느 브랜치에도 달려 있지 않아 지우면 되찾을 수 없다. */
    STASHED("stash 엔트리"),

    /** 유효한 저장소로 열리지 않아 무엇이 들었는지 판정할 수 없다. 모르면 지우지 않는다. */
    UNDECIDABLE("판정 불가"),
}

/** 보존해야 할 것 하나. [path] 는 **부모 워킹트리 기준** 상대 경로다. */
internal data class PreservedEntry(val path: String, val reason: PreservationReason) {

    fun under(prefix: String): PreservedEntry = copy(path = "$prefix/$path")

    override fun toString(): String = "$path(${reason.label})"
}

/**
 * [path] 의 서브모듈 아래에서 **보존해야 할 것을 전부** 모은다. 비어 있으면 그 아래의 모든 것을
 * 저장소가 되찾아 줄 수 있다는 뜻이다.
 *
 * 서브모듈의 데이터는 워킹트리와 `.git/modules` Git 디렉터리에 나뉠 수 있으므로 **둘 다** 본다.
 * 두 곳이 모두 없을 때만 빈 목록이 정당하다. 존재하는 곳을 유효한 저장소로 열지 못하면 판정 불가다.
 * 그 실패를 "깨끗함" 으로 읽으면 남아 있던 사용자 파일 또는 로컬 커밋을 통째로 지우게 된다.
 *
 * 되찾을 수 있는지의 기준점은 **부모 인덱스가 기록한 gitlink 커밋**이라 그것을 먼저 읽어 내려보낸다.
 * 중첩 서브모듈도 각자의 부모가 기록한 커밋으로 판정한다.
 */
internal fun Repository.scanPreserved(path: String, name: String = submoduleNameOf(path)): List<PreservedEntry> {
    val recorded = recordedGitlink(path)
    val scannedGitDirectories = mutableSetOf<File>()
    val entries = buildList {
        if (submoduleWorkTreeDirectory(path).exists()) {
            openSubmoduleRepository(path)?.use { child ->
                scannedGitDirectories += child.directory.canonicalFile
                addAll(child.preservedEntries(recorded).map { entry -> entry.under(path) })
            } ?: add(PreservedEntry(path, PreservationReason.UNDECIDABLE))
        }
        submoduleGitDirectories(path, name)
            .filter(File::exists)
            .filter { directory -> directory.canonicalFile !in scannedGitDirectories }
            .forEach { directory -> addAll(scanGitDirectory(path, directory, recorded)) }
    }
    return entries.distinct()
}

/**
 * 워킹트리가 있으면 그 Git 디렉터리를 열어 검사한다. 열지 못한 이유(손상·권한)를 여기서 구분하지
 * 않는다 — 어느 쪽이든 **무엇이 들었는지 모른다** 는 결론이 같고, 결론이 같으면 행동도 같다.
 */
private fun Repository.scanGitDirectory(
    path: String,
    directory: File,
    recorded: ObjectId?,
): List<PreservedEntry> =
    openGitDirectory(directory)
        ?.use { child -> child.preservedEntries(recorded).map { entry -> entry.under(path) } }
        ?: listOf(PreservedEntry(path, PreservationReason.UNDECIDABLE))

private fun Repository.openSubmoduleRepository(path: String): Repository? =
    runCatching { SubmoduleWalk.getSubmoduleRepository(this, path) }.getOrNull()

private fun openGitDirectory(directory: File): Repository? =
    runCatching { FileRepositoryBuilder().setGitDir(directory).setMustExist(true).build() }.getOrNull()

/**
 * 부모 인덱스가 기록한 서브모듈 커밋. 이것이 곧 "지운 뒤에도 저장소가 되찾아 줄 수 있는" 범위의
 * 기준점이다. gitlink 가 아닌 엔트리는 기준점이 될 수 없으므로 null 로 남긴다.
 */
private fun Repository.recordedGitlink(path: String): ObjectId? =
    readDirCache().getEntry(path)?.takeIf { entry -> entry.fileMode == FileMode.GITLINK }?.objectId

/**
 * 파일 상태와 **커밋 상태**를 함께 본다. 파일만 보고 "깨끗함" 을 판정하면 서브모듈 안에만 있던 커밋을
 * 잃는다([historyEntries]).
 */
private fun Repository.preservedEntries(recorded: ObjectId?): List<PreservedEntry> =
    (statusEntries() + historyEntries(recorded) + nestedEntries()).distinct()

/**
 * 중첩 서브모듈을 [SubmoduleWalk.IgnoreSubmoduleMode.ALL] 로 가리지 않는다 — 가리면 중첩 안의
 * 커밋되지 않은 변경이 "깨끗함" 으로 통과한다. 중첩 자신은 [nestedEntries] 가 다시 내려가며 본다.
 */
private fun Repository.statusEntries(): List<PreservedEntry> {
    val status = Git.wrap(this).use { git ->
        git.status().setIgnoreSubmodules(SubmoduleWalk.IgnoreSubmoduleMode.NONE).call()
    }
    return entriesOf(status.uncommittedChanges + status.conflicting, PreservationReason.UNCOMMITTED) +
        entriesOf(status.untracked + status.untrackedFolders, PreservationReason.UNTRACKED) +
        entriesOf(status.ignoredNotInIndex, PreservationReason.IGNORED)
}

/** [Status] 가 돌려준 경로는 그 저장소 기준이라, 부모 기준으로 올리는 일은 [scanPreserved] 가 한다. */
private fun entriesOf(paths: Set<String>, reason: PreservationReason): List<PreservedEntry> =
    paths.sorted().map { relative -> PreservedEntry(relative, reason) }

private fun Repository.nestedEntries(): List<PreservedEntry> =
    readSubmodulePaths().flatMap(::scanPreserved)
