package dev.undine.infrastructure.git.submodule

import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * 서브모듈 추가가 손대기 **전**의 상태. 추가가 실패하면 이 스냅샷으로 되돌린다 —
 * 절반만 붙은 서브모듈은 다음 clone 에서 이상하게 동작한다.
 *
 * 보상 대상은 셋이다 — `.gitmodules`([modules]) · 대상 경로의 **gitlink 인덱스
 * 엔트리**([pathIndexEntry]) · 그 경로의 **submodule 설정 섹션**([configSection]). 셋 다 "원래
 * 없었다" 와 "원래 있었다" 를 구분해 담는다. 없던 것만 지우고, 있던 것은 값까지 되돌린다 — 무조건
 * 지우면 원래 있던 서브모듈이 사라지고, 있었다는 이유로 건너뛰면 추가가 덮어쓴 설정이 그대로 남는다.
 * 되돌린 것이 원래 상태가 아니면 되돌린 것이 아니다.
 *
 * 디렉터리도 마찬가지로 **이 추가가 만든 것만** 지운다.
 */
internal class SubmoduleAddRollback private constructor(
    private val repository: Repository,
    private val path: String,
    private val modules: ModulesFileSnapshot,
    private val pathIndexEntry: IndexEntrySnapshot?,
    private val existingDirectories: Set<File>,
    private val configSection: ConfigSectionSnapshot?,
) {

    /**
     * 되돌린다. **설정 복원이 먼저이고, 그것만은 실패하면 거기서 멈춘다** — 설정을 디스크에 못 썼는데
     * gitlink·워킹트리·`.git/modules`·`.gitmodules` 를 계속 정리하면 설정과 파일시스템이 갈라져,
     * 남은 선언이 이미 지워진 서브모듈을 계속 가리킨다. 저장이 실패해도 메모리 설정은 디스크 값으로
     * 되돌아가 있으므로([removeConfigSection]·[restoreConfigSection]) 호출 전 상태가 그대로 남는다.
     *
     * 설정을 되돌린 뒤의 단계들은 서로 독립이라 한 단계가 실패해도 **남은 보상을 모두 시도**한다 —
     * 거기서 멈추면 절반만 붙은 상태가 그대로 남는다. 복구까지 실패하면 원본 [failure] 에
     * suppressed 로 붙인다 — 원인을 바꿔치기하지도, 조용히 삼키지도 않는다.
     */
    fun restoreAfter(failure: Throwable) {
        val configFailures = attemptAll(listOf<() -> Unit> { restoreConfigSection() })
        configFailures.forEach(failure::addSuppressed)
        if (configFailures.isNotEmpty()) return
        attemptAll(restoreSteps()).forEach(failure::addSuppressed)
    }

    /** 설정을 되돌린 뒤의 단계는 서로 독립이다 — 앞 단계의 실패가 뒤 단계를 막지 않도록 나눠 둔다. */
    private fun restoreSteps(): List<() -> Unit> = buildList {
        add { restorePathIndexEntry() }
        repository.submoduleDirectories(path)
            .filterNot(existingDirectories::contains)
            .forEach { directory -> add { deleteRecursively(directory) } }
        addAll(modules.restoreSteps(repository))
    }

    private fun restorePathIndexEntry() = repository.restoreIndexEntry(path, pathIndexEntry)

    private fun restoreConfigSection() = repository.restoreConfigSection(path, configSection)

    companion object {

        /** JGit 의 add 는 서브모듈 이름을 경로와 같게 둔다 — 설정 섹션 이름도 경로다. */
        fun capture(repository: Repository, path: String): SubmoduleAddRollback = SubmoduleAddRollback(
            repository = repository,
            path = path,
            modules = ModulesFileSnapshot.capture(repository),
            pathIndexEntry = repository.readIndexEntry(path),
            existingDirectories = repository.submoduleDirectories(path).filter(File::exists).toSet(),
            configSection = repository.readConfigSection(path),
        )
    }
}
