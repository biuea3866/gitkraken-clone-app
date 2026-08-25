package dev.undine.infrastructure.git.submodule

import dev.undine.domain.UndineException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FileUtils
import java.io.File

private const val DELETE_MODE = FileUtils.RECURSIVE or FileUtils.SKIP_MISSING or FileUtils.RETRY

internal fun Repository.modulesFile(): File = File(workTree, Constants.DOT_GIT_MODULES)

/** `.gitmodules` 가 없으면 null 이다 — 빈 설정으로 뭉개면 "선언이 없다" 와 "비었다" 가 섞인다. */
internal fun Repository.modulesConfig(): FileBasedConfig? =
    modulesFile()
        .takeIf(File::exists)
        ?.let { file -> FileBasedConfig(file, fs).also(FileBasedConfig::load) }

/** `.gitmodules` 변경은 곧바로 스테이징한다 — 워킹트리와 인덱스가 어긋난 채 남지 않게. */
internal fun Repository.stageModulesFile() {
    Git.wrap(this).use { git -> git.add().addFilepattern(Constants.DOT_GIT_MODULES).call() }
}

/**
 * `.gitmodules` 가 손대기 전에 갖고 있던 워킹트리 내용과 인덱스 엔트리. 둘은 어긋나 있을 수 있어
 * — unstaged 수정, 또는 워킹트리에서만 지워진 파일 — **각자의 스냅샷으로** 따로 되돌린다.
 * 워킹트리만 되돌리면 호출 전 스테이징 상태가 바뀐다.
 *
 * 파일의 **부재와 빈 내용을 구분해** 들고 다닌다([content] 가 null 이면 "그 파일이 없었다").
 * 내용이 비었다는 이유로 원래 있던 추적 파일을 지우면 사용자가 잃는 것이 서브모듈보다 크다.
 *
 * 추가 보상과 제거 보상이 같은 파일을 되돌리므로 한 타입을 공유한다 — 한쪽만 고치면 다른 쪽이 갈라진다.
 */
internal class ModulesFileSnapshot(
    private val content: ByteArray?,
    private val indexEntry: IndexEntrySnapshot?,
) {

    /** 워킹트리와 인덱스는 서로 독립이라 한쪽이 실패해도 나머지를 되돌리도록 단계로 나눠 둔다. */
    fun restoreSteps(repository: Repository): List<() -> Unit> = listOf(
        { restoreWorkingTree(repository) },
        { restoreIndexEntry(repository) },
    )

    /** 두 단계를 전부 시도하고, 하나라도 못 되돌렸으면 성공으로 보고하지 않는다. */
    fun restore(repository: Repository) = completeAll(restoreSteps(repository))

    private fun restoreWorkingTree(repository: Repository) {
        val modules = repository.modulesFile()
        val bytes = content ?: return deleteRecursively(modules)
        modules.writeBytes(bytes)
    }

    private fun restoreIndexEntry(repository: Repository) =
        repository.restoreIndexEntry(Constants.DOT_GIT_MODULES, indexEntry)

    companion object {

        fun capture(repository: Repository): ModulesFileSnapshot = ModulesFileSnapshot(
            content = repository.modulesFile().takeIf(File::exists)?.readBytes(),
            indexEntry = repository.readIndexEntry(Constants.DOT_GIT_MODULES),
        )
    }
}

/**
 * `.gitmodules` 가 부르는 이름. 이름과 경로는 다를 수 있어(외부 git 이 만든 저장소) 경로로 되찾고,
 * 선언이 없으면 JGit 의 기본값인 경로를 그대로 쓴다.
 */
internal fun Repository.submoduleNameOf(path: String): String =
    modulesConfig()?.let { modules ->
        modules.getSubsections(ConfigConstants.CONFIG_SUBMODULE_SECTION).firstOrNull { name ->
            modules.getString(ConfigConstants.CONFIG_SUBMODULE_SECTION, name, ConfigConstants.CONFIG_KEY_PATH) == path
        }
    } ?: path

/**
 * [base] 안으로 확정된 경로. 확정하지 못하면 연산 자체를 거부한다.
 *
 * 서브모듈 경로와 이름은 **신뢰할 수 없는 입력**이다 — `.gitmodules` 는 저장소에 커밋돼 오는 파일이라
 * clone 한 남의 저장소가 그 내용을 정하고, `add()` 의 경로는 사용자가 준다. 그런데 여기서 나온 값이
 * 그대로 재귀 삭제 대상이 되므로, `..` 이나 절대 경로가 섞이면 기준 디렉터리 밖의 사용자 데이터가
 * 사라진다. 정규화는 밖으로 나가는 심볼릭 링크도 함께 드러낸다.
 *
 * 기준 디렉터리 **자신**도 거부한다 — 빈 경로나 `.` 는 `.git/modules` 통째로를 삭제 대상으로 만든다.
 * 지운 데이터는 되돌릴 수 없으므로 **모르면 지우지 않는다**.
 */
private fun File.resolveInside(candidate: String): File {
    val root = canonicalFile
    val target = File(this, candidate).canonicalFile
    if (target == root || !target.toPath().startsWith(root.toPath())) {
        throw UndineException.StateViolation("서브모듈 경로가 허용된 디렉터리 밖을 가리킵니다: '$candidate'")
    }
    return target
}

/** 서브모듈이 차지하는 부모 워킹트리 안의 디렉터리. 워킹트리를 벗어나는 경로는 거부한다. */
internal fun Repository.submoduleWorkTreeDirectory(path: String): File = workTree.resolveInside(path)

/** 서브모듈 하나가 차지하는 디렉터리 전부 — 워킹트리와 `.git/modules` 아래 하위 저장소. */
internal fun Repository.submoduleDirectories(path: String): List<File> =
    listOf(submoduleWorkTreeDirectory(path)) + submoduleGitDirectories(path, path)

/**
 * 하위 저장소의 git 디렉터리 후보. JGit 은 경로로, git CLI 는 `.gitmodules` 의 이름으로 만들기 때문에
 * 둘 다 본다 — 어느 도구가 만든 서브모듈이든 잔재를 남기지 않아야 한다.
 *
 * 두 후보 모두 `.git/modules` 안으로 확정한다. `.gitmodules` 의 이름은 남의 저장소가 정하므로
 * 검증 없이 삭제 대상으로 쓰면 그 파일이 우리 삭제 범위를 정하게 된다.
 */
internal fun Repository.submoduleGitDirectories(path: String, name: String): List<File> {
    val modules = File(commonDirectory, Constants.MODULES)
    return listOf(path, name).distinct().map(modules::resolveInside)
}

internal fun deleteRecursively(target: File) {
    FileUtils.delete(target, DELETE_MODE)
}
