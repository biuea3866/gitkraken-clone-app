package dev.undine.infrastructure.git.submodule

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.submodule.SubmoduleWalk
import org.eclipse.jgit.transport.CredentialsProvider

/** `submodule.<name>.branch`. JGit 상수가 없는 키라 여기서 이름을 고정한다. */
private const val MODULES_BRANCH_KEY = "branch"

/**
 * 서브모듈을 쓸 수 있는 상태로 만든다 — 원격 주소를 저장소 설정에 등록하고, 없으면 clone 한 뒤
 * 부모가 기록한 커밋으로 체크아웃한다. 이미 초기화돼 있으면 두 명령 모두 사실상 멱등이다.
 */
internal fun Repository.initializeSubmodule(path: String, recursive: Boolean, credentials: CredentialsProvider) {
    Git.wrap(this).use { git ->
        git.submoduleInit().addPath(path).call()
        git.submoduleUpdate().addPath(path).setCredentialsProvider(credentials).call()
    }
    if (!recursive) return
    forEachNested(path) { child, nested -> child.initializeSubmodule(nested, recursive = true, credentials) }
}

/**
 * 이미 초기화된 서브모듈을 부모가 기록한 커밋으로 맞춘다.
 *
 * 등록되지 않은(=초기화되지 않은) 서브모듈은 JGit 의 update 가 건너뛴다. 이 계약에서 초기화는
 * [initializeSubmodule] 의 몫이므로 그 동작을 그대로 둔다 — 중첩까지 조용히 clone 하지 않는다.
 */
internal fun Repository.updateSubmodule(path: String, recursive: Boolean, credentials: CredentialsProvider) {
    Git.wrap(this).use { git ->
        git.submoduleUpdate().addPath(path).setCredentialsProvider(credentials).call()
    }
    if (!recursive) return
    forEachNested(path) { child, nested -> child.updateSubmodule(nested, recursive = true, credentials) }
}

/**
 * `.gitmodules` · 저장소 설정 · 워킹트리에 서브모듈을 붙인다.
 *
 * JGit 의 add 는 clone 한 하위 저장소 핸들을 돌려준다 — 이 함수가 소유하고 바로 닫는다.
 */
internal fun Repository.addSubmodule(
    url: String,
    path: String,
    branch: String?,
    credentials: CredentialsProvider,
) {
    Git.wrap(this).use { git ->
        git.submoduleAdd()
            .setURI(url)
            .setPath(path)
            .setCredentialsProvider(credentials)
            .call()
            ?.use { /* clone 한 하위 저장소 핸들은 이 함수가 소유한다 — 열자마자 닫는다. */ }
    }
    if (branch != null) recordSubmoduleBranch(path, branch)
}

/**
 * `git submodule add -b` 와 같은 기록이다. JGit 의 add 명령에는 브랜치 옵션이 없어 `.gitmodules` 에
 * 직접 적는다.
 */
private fun Repository.recordSubmoduleBranch(path: String, branch: String) {
    val modules = FileBasedConfig(modulesFile(), fs)
    modules.load()
    modules.setString(ConfigConstants.CONFIG_SUBMODULE_SECTION, submoduleNameOf(path), MODULES_BRANCH_KEY, branch)
    modules.save()
    stageModulesFile()
}

private fun Repository.forEachNested(path: String, block: (Repository, String) -> Unit) {
    SubmoduleWalk.getSubmoduleRepository(this, path)?.use { child ->
        child.readSubmodulePaths().forEach { nested -> block(child, nested) }
    }
}
