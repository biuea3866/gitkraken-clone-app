package dev.undine.infrastructure.git.submodule

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import java.io.IOException

internal fun Repository.removeConfigSection(name: String) {
    updateConfigSection(name) { config.unsetSection(ConfigConstants.CONFIG_SUBMODULE_SECTION, name) }
}

internal fun Repository.restoreConfigSection(snapshot: ConfigSectionSnapshot) {
    updateConfigSection(snapshot.name) { snapshot.restoreInto(config) }
}

/** 원래 섹션이 있었으면 그 값으로, 없었으면 부재로 되돌린다 — "없었다" 도 복원해야 할 상태다. */
internal fun Repository.restoreConfigSection(name: String, snapshot: ConfigSectionSnapshot?) {
    snapshot?.let(::restoreConfigSection) ?: removeConfigSection(name)
}

/**
 * `submodule.<name>` 섹션을 바꾸고 디스크에 쓴다. 저장에 실패하면 **메모리 변경을 호출 전 값으로
 * 되돌린 뒤** 원래 실패를 그대로 올린다.
 *
 * 공유 [Repository.config] 가 디스크와 갈라진 채 남으면 같은 저장소를 쓰는 후속 조회가 디스크에 없는
 * 값을 읽는다. 되돌리기를 `config.load()` 로 하지 않는 이유: JGit 은 파일 내용 해시가 그대로면 다시
 * 파싱하지 않는데, 저장이 실패해 파일이 그대로인 이 경우가 정확히 그 no-op 에 걸린다.
 *
 * 되돌리기까지 실패하면 원인을 바꿔치기하지 않고 suppressed 로 붙인다 — 조용히 삼키지 않는다.
 */
private fun Repository.updateConfigSection(name: String, change: () -> Unit) {
    val previous = readConfigSection(name)
    change()
    try {
        config.save()
    } catch (failure: IOException) {
        attemptAll(listOf<() -> Unit> { rewindConfigSection(name, previous) }).forEach(failure::addSuppressed)
        throw failure
    }
}

/** 원래 값이 있었으면 그 값으로, 없었으면 부재로 되돌린다 — "없었다" 도 복원해야 할 상태다. */
private fun Repository.rewindConfigSection(name: String, previous: ConfigSectionSnapshot?) {
    previous?.restoreInto(config) ?: config.unsetSection(ConfigConstants.CONFIG_SUBMODULE_SECTION, name)
}

/**
 * `submodule.<name>` 설정 섹션 하나를 통째로 담아 둔 스냅샷. 되돌리기가 "섹션이 있었다" 는 사실만
 * 보고 넘어가면 추가가 덮어쓴 값이 그대로 남는다 — 사용자가 이미 갖고 있던 설정을 이 연산이 바꾼 채
 * 두는 것이다. 섹션이 애초에 없었다는 사실은 이 타입이 아니라 null 이 표현한다.
 */
internal class ConfigSectionSnapshot(
    val name: String,
    private val values: Map<String, List<String>>,
) {

    /** 스냅샷 시점 그대로 되살린다 — 지금 값을 지우고 담아 둔 값만 다시 쓴다. */
    internal fun restoreInto(config: Config) {
        config.unsetSection(ConfigConstants.CONFIG_SUBMODULE_SECTION, name)
        values.forEach { (key, value) ->
            config.setStringList(ConfigConstants.CONFIG_SUBMODULE_SECTION, name, key, value)
        }
    }
}

/** 그 섹션이 없으면 null — "설정이 없었다" 는 것도 복원해야 할 상태다. */
internal fun Repository.readConfigSection(name: String): ConfigSectionSnapshot? =
    name.takeIf { it in config.getSubsections(ConfigConstants.CONFIG_SUBMODULE_SECTION) }
        ?.let { section ->
            ConfigSectionSnapshot(
                name = section,
                values = config.getNames(ConfigConstants.CONFIG_SUBMODULE_SECTION, section).associateWith { key ->
                    config.getStringList(ConfigConstants.CONFIG_SUBMODULE_SECTION, section, key).toList()
                },
            )
        }
