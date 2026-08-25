package dev.undine.infrastructure.git.submodule

import dev.undine.domain.UndineException
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 열거된 항목 하나만 지운다 — 재귀 플래그를 주지 않아 열거되지 않은 것이 딸려 나갈 수 없다. */
private const val ENTRY_DELETE_MODE = FileUtils.SKIP_MISSING or FileUtils.RETRY

/**
 * 서브모듈 하나를 부모에서 떼어낸다.
 *
 * 순서가 곧 안전장치다 — **경로 확정 → 보존 스캔 → 확인 → 열거 → 되돌릴 수 있는 정리 → 삭제.**
 * 되돌릴 수 없는 단계(삭제)를 맨 뒤에 두어, 앞 단계가 실패하면 호출 전 상태로 되돌리고 파일은
 * 손대지 않은 채 끝난다.
 *
 * 삭제 단계도 실패할 수 있으므로 **메타데이터 정리와 삭제를 같은 보상으로 감싼다** — 둘 중 어느
 * 단계에서 멈추든 선언은 호출 전 상태로 돌아간다([SubmoduleRemoveRollback.guard]).
 *
 * 검사와 실행 사이에 **다른 프로세스**가 워킹트리를 바꾸는 상황은 방어하지 않는다(명시적 비목표).
 * 앱 자신의 전이는 `GitAccess` 임계구역 하나 안에서 직렬화되므로 여기에 락을 더 걸지 않는다.
 */
internal fun Repository.removeSubmodule(
    path: String,
    confirmed: Boolean,
    delete: (File) -> Unit = ::deleteEntry,
) {
    val name = submoduleNameOf(path)
    val targets = removalTargets(path, name)
    validateRemovable(path, name, confirmed)
    val doomed = targets.flatMap(::entriesDeepestFirst)
    val rollback = SubmoduleRemoveRollback.capture(this, path, name)
    rollback.guard {
        detachSubmodule(path, name)
        completeAll(doomed.map { entry -> { delete(entry) } })
    }
}

/**
 * 이 제거가 손댈 수 있는 기준 디렉터리. `.gitmodules` 의 subsection 이름은 **clone 해 온 남의 저장소가
 * 정하는 값**이라, 그것이 삭제 범위를 정하게 두면 안 된다 — 확정에 실패하면 여기서 연산을 거부한다.
 */
private fun Repository.removalTargets(path: String, name: String): List<File> =
    listOf(submoduleWorkTreeDirectory(path)) + submoduleGitDirectories(path, name)

/**
 * 보존해야 할 것이 하나라도 있으면 [confirmed] 와 무관하게 거부한다 — 확인은 "지워도 된다" 는 뜻이지
 * "무엇이 지워지는지 안다" 는 뜻이 아니다. 무엇 때문에 막혔는지 목록으로 알린다.
 */
private fun Repository.validateRemovable(path: String, name: String, confirmed: Boolean) {
    val preserved = scanPreserved(path, name)
    if (preserved.isNotEmpty()) {
        throw UndineException.StateViolation(
            "서브모듈 '$path' 아래에 보존해야 할 항목이 있어 제거할 수 없습니다: ${preserved.joinToString()}",
        )
    }
    if (!confirmed) {
        throw UndineException.StateViolation("서브모듈 제거는 되돌릴 수 없어 확인이 필요합니다: '$path'")
    }
}

/**
 * 되돌릴 수 있는 세 곳을 정리한다 — 저장소 설정 · `.gitmodules` · 인덱스 gitlink.
 *
 * 설정 저장이 먼저다. 저장에 실패했는데 나머지를 계속하면 설정과 파일시스템이 갈라져, 남은 선언이
 * 이미 사라진 서브모듈을 계속 가리킨다.
 */
private fun Repository.detachSubmodule(path: String, name: String) {
    removeConfigSection(name)
    removeModulesSection(name)
    removeIndexEntry(path)
}

/**
 * 선언이 하나도 남지 않으면 `.gitmodules` 파일 자체를 지우고 인덱스에서도 뺀다 — `git` 이 마지막
 * 서브모듈을 뗄 때 하는 것과 같다. 빈 파일을 남기면 다음 제거가 "선언이 없다" 와 "비었다" 를 구분하지
 * 못한다.
 */
private fun Repository.removeModulesSection(name: String) {
    val modules = modulesConfig() ?: return
    modules.unsetSection(ConfigConstants.CONFIG_SUBMODULE_SECTION, name)
    if (modules.getSubsections(ConfigConstants.CONFIG_SUBMODULE_SECTION).isEmpty()) {
        deleteRecursively(modulesFile())
        removeIndexEntry(Constants.DOT_GIT_MODULES)
        return
    }
    modules.save()
    stageModulesFile()
}

/**
 * [base] 아래에 **지금 실제로 있는 것만** 깊은 것부터 열거한다. 이 목록이 곧 삭제 범위다 —
 * "그 아래 전부" 를 재귀 삭제하지 않으므로, 열거에 없는 것은 어떤 경우에도 지워지지 않는다.
 *
 * 심볼릭 링크는 **따라가지 않고 링크 자신을** 열거한다. 따라가면 기준 밖 사용자 데이터가 삭제 목록에
 * 들어온다. 열거 중 하나라도 읽지 못하면 그대로 실패한다 — 삭제 전이므로 아무것도 잃지 않는다.
 */
private fun entriesDeepestFirst(base: File): List<File> {
    val root = base.toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val collected = mutableListOf<File>()
    Files.walkFileTree(root, DeepestFirstCollector(collected))
    return collected
}

/** 자식을 먼저 담고 디렉터리는 빠져나올 때 담는다 — 그대로 삭제 순서가 된다. */
private class DeepestFirstCollector(private val collected: MutableList<File>) : SimpleFileVisitor<Path>() {

    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
        collected += file.toFile()
        return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
        if (failure != null) throw failure
        collected += directory.toFile()
        return FileVisitResult.CONTINUE
    }
}

/**
 * 재귀 없이 항목 하나만 지운다. 디렉터리에 열거되지 않은 것이 남아 있으면 지워지지 않고 실패한다 —
 * 남은 디렉터리는 사람이 지울 수 있지만 지운 사용자 파일은 되돌릴 수 없다. 실패는 삼키지 않고
 * [completeAll] 이 모아 올린다.
 */
private fun deleteEntry(target: File) {
    FileUtils.delete(target, ENTRY_DELETE_MODE)
}

/**
 * 제거가 손대기 **전**의 되돌릴 수 있는 상태. 삭제 앞 단계가 실패하면 이 스냅샷으로 호출 전 상태를
 * 되살린다 — 절반만 떼어낸 서브모듈은 다음 조회·clone 에서 이상하게 동작한다.
 *
 * 셋 다 "원래 없었다" 와 "원래 있었다" 를 구분해 담는다([ConfigSectionSnapshot]·[IndexEntrySnapshot] 이
 * null 로 부재를 표현한다). 되돌린 것이 원래 상태가 아니면 되돌린 것이 아니다.
 */
internal class SubmoduleRemoveRollback private constructor(
    private val repository: Repository,
    private val path: String,
    private val name: String,
    private val modules: ModulesFileSnapshot,
    private val pathIndexEntry: IndexEntrySnapshot?,
    private val configSection: ConfigSectionSnapshot?,
) {

    /**
     * 되돌린다. **설정 복원이 먼저이고, 그것만은 실패하면 거기서 멈춘다** — 설정을 디스크에 못 썼는데
     * `.gitmodules`·인덱스를 되살리면 설정과 나머지가 갈라진다. 저장이 실패해도 메모리 설정은 디스크
     * 값으로 되돌아가 있으므로([restoreConfigSection]) 호출 전 상태가 그대로 남는다.
     *
     * 그 뒤 단계들은 서로 독립이라 한 단계가 실패해도 **남은 보상을 모두 시도**한다. 복구까지 실패하면
     * 원본 [failure] 에 suppressed 로 붙인다 — 원인을 바꿔치기하지도, 조용히 삼키지도 않는다.
     */
    fun restoreAfter(failure: Throwable) {
        val configFailures = attemptAll(listOf<() -> Unit> { restoreConfigSection() })
        configFailures.forEach(failure::addSuppressed)
        if (configFailures.isNotEmpty()) return
        attemptAll(restoreSteps()).forEach(failure::addSuppressed)
    }

    private fun restoreSteps(): List<() -> Unit> =
        listOf<() -> Unit> { restorePathIndexEntry() } + modules.restoreSteps(repository)

    private fun restoreConfigSection() = repository.restoreConfigSection(name, configSection)

    private fun restorePathIndexEntry() = repository.restoreIndexEntry(path, pathIndexEntry)

    companion object {

        /**
         * 설정 섹션 이름은 `.gitmodules` 가 부르는 이름이다 — 외부 git 이 만든 저장소에서는 경로와
         * 다를 수 있어, 제거와 복원이 **같은 이름**을 봐야 한다.
         */
        fun capture(repository: Repository, path: String, name: String): SubmoduleRemoveRollback =
            SubmoduleRemoveRollback(
                repository = repository,
                path = path,
                name = name,
                modules = ModulesFileSnapshot.capture(repository),
                pathIndexEntry = repository.readIndexEntry(path),
                configSection = repository.readConfigSection(name),
            )
    }
}

/**
 * 제거의 되돌릴 수 있는 정리와 파일 삭제는 하나의 논리 전이다. 어느 쪽에서 실패해도 메타데이터를
 * 호출 전 상태로 되돌린 뒤 원래 실패를 그대로 올린다. 삭제된 파일 자체는 복원할 수 없으므로,
 * [completeAll] 은 남은 열거 항목을 계속 시도해 부분 정리를 최소화한다.
 */
internal inline fun SubmoduleRemoveRollback.guard(block: () -> Unit) {
    runCatching(block).onFailure(::restoreAfter).getOrThrow()
}
