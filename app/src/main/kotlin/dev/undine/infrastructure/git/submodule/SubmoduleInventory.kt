package dev.undine.infrastructure.git.submodule

import dev.undine.domain.UndineException
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleState
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.submodule.SubmoduleWalk

/** clone 되지 않았거나 아직 HEAD 가 없는 상태 — 나머지 두 축은 판정 대상이 아니다. */
private val NOT_INITIALIZED = SubmoduleState(
    initialized = false,
    locallyModified = false,
    divergedFromRecorded = false,
)

/**
 * 부모 인덱스가 아는 서브모듈 전부를 인덱스 순서(경로 오름차순)로 읽는다.
 *
 * `.gitmodules` 에만 있고 인덱스에 gitlink 가 없는 항목은 나오지 않는다 — git 의 상태 조회와 같다.
 */
internal fun Repository.readSubmodules(): List<Submodule> =
    SubmoduleWalk.forIndex(this).use { walk ->
        buildList {
            while (walk.next()) add(walk.readSubmodule())
        }
    }

/** 재귀 하강에는 경로만 필요하다 — 하위 저장소를 열어 상태를 판정하는 비용을 치르지 않는다. */
internal fun Repository.readSubmodulePaths(): List<String> =
    SubmoduleWalk.forIndex(this).use { walk ->
        buildList {
            while (walk.next()) add(walk.path)
        }
    }

/**
 * @throws UndineException.NotFound 인덱스에 그 경로의 서브모듈이 없을 때. 조회·조작 대상이 이미
 *   사라진 경우이며, 앱 버그인 `GitOperationFailed` 와 구분해야 화면이 다르게 안내한다.
 */
internal fun Repository.requireSubmodule(path: String): Submodule =
    readSubmodules().firstOrNull { submodule -> submodule.path == path }
        ?: throw UndineException.NotFound(UndineException.NotFound.Kind.SUBMODULE, path)

/** 하위 저장소 핸들은 이 함수가 열고 이 함수가 닫는다 — 상태 판정 밖으로 새지 않는다. */
private fun SubmoduleWalk.readSubmodule(): Submodule {
    val recorded: ObjectId? = objectId
    val state = repository?.use { child -> child.submoduleState(recorded) } ?: NOT_INITIALIZED
    return Submodule(path = path, url = modulesUrl, state = state)
}

/**
 * 디렉터리는 있는데 HEAD 가 없는 상태(체크아웃 전 빈 저장소)도 미초기화로 본다 —
 * 사용자가 해야 할 일이 "초기화" 로 같기 때문이다.
 */
private fun Repository.submoduleState(recorded: ObjectId?): SubmoduleState {
    val head = resolve(Constants.HEAD) ?: return NOT_INITIALIZED
    return SubmoduleState(
        initialized = true,
        locallyModified = !isWorkingTreeClean(),
        divergedFromRecorded = recorded != null && recorded != head,
    )
}

/**
 * 중첩 서브모듈의 상태는 제외하고 본다. 중첩 서브모듈이 미초기화이거나 어긋난 것은 **그 서브모듈의**
 * 상태이지 이 서브모듈의 커밋되지 않은 변경이 아니다 — 섞으면 사용자가 어디를 고쳐야 할지 모른다.
 */
private fun Repository.isWorkingTreeClean(): Boolean =
    Git.wrap(this).use { git ->
        git.status().setIgnoreSubmodules(SubmoduleWalk.IgnoreSubmoduleMode.ALL).call().isClean
    }
