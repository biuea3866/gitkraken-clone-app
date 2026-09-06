package dev.undine.infrastructure.git.patch

import dev.undine.domain.UndineException
import org.eclipse.jgit.lib.ObjectChecker
import org.eclipse.jgit.lib.Repository
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 패치는 **신뢰할 수 없는 입력**이다. 적용 전에 모든 경로가 워킹트리 안에 머무는지 판정한다.
 *
 * 금지 이름 목록을 우리가 만들지 않는다. `.git` 은 어느 깊이에서도, 어떤 변형(`.GIT`·`.git.`·
 * `git~1`)으로도 트리 항목이 될 수 없다는 것이 **git 자신의 불변식**이고, [ObjectChecker] 가 그
 * 판정을 그대로 구현한다. 우리가 목록을 만들면 변형 하나를 빠뜨리는 순간 뚫린다.
 *
 * [ObjectChecker] 만으로는 부족한 축이 둘 있어 함께 본다:
 * - **정규화 후 워킹트리 루트 아래**인가 — 절대 경로가 여기서 걸린다.
 * - **조상 중 심볼릭 링크가 없는가** — 링크를 경유하면 이름은 루트 아래인데 실제 쓰기는 바깥으로 나간다.
 *
 * 셋 중 **어느 하나라도 판정할 수 없으면 거부**한다. 판정 불가를 통과로 해석하지 않는다.
 */
internal fun Repository.requireSafePatchPaths(paths: List<String>) {
    val root = workTreeRoot()
    val checker = ObjectChecker()
    paths.forEach { path ->
        checker.requireValidTreePath(path)
        root.requireInsideRoot(path)
        if (root.hasSymbolicLinkAncestor(path)) rejectPath("심볼릭 링크를 경유합니다: '$path'")
    }
}

/**
 * 워킹트리 루트의 **실제 경로**. 루트 자체가 심볼릭 링크를 경유하면 이후 모든 비교가 어긋나므로
 * 여기서 한 번 풀어 둔다. 풀 수 없으면 판정 불가이므로 거부한다.
 */
internal fun Repository.workTreeRoot(): Path =
    runCatching { workTree.toPath().toRealPath() }
        .getOrElse { failure -> rejectPath("워킹트리 경로를 확인할 수 없습니다: ${failure.message}") }

private fun ObjectChecker.requireValidTreePath(path: String) {
    runCatching { checkPath(path) }
        .onFailure { failure -> rejectPath("Git 트리에 넣을 수 없는 이름입니다('$path'): ${failure.message}") }
}

private fun Path.requireInsideRoot(path: String) {
    val resolved = runCatching { resolve(path).normalize() }
        .getOrElse { failure -> rejectPath("경로를 정규화할 수 없습니다('$path'): ${failure.message}") }
    if (!resolved.startsWith(this)) rejectPath("워킹트리 밖을 가리킵니다: '$path'")
}

/**
 * 루트에서 [path] 로 내려가며 **조상**이 심볼릭 링크인지 본다. 대상 자신은 링크여도 된다 —
 * 승격·복원은 링크를 따라가지 않고 링크 자체를 갈아 끼우므로 바깥으로 새지 않는다.
 *
 * `NOFOLLOW` 만으로는 이 축이 덮이지 않는다. 그 옵션은 경로의 **마지막 요소**에만 걸리므로,
 * `a` 가 링크면 `root.resolve("a/b")` 는 이미 바깥을 가리킨다. 그래서 워킹트리를 훑고 지우는 쪽도
 * ([workingTreeFilesUnder]) 이 판정을 함께 쓴다 — 링크 조상 아래는 저장소 항목이 아니다.
 *
 * 조상이 심볼릭 **링크가 아닌** 일반 파일인 것은 여기서 막지 않는다. `a` 를 지우고 `a/b` 를 만드는
 * 정상적인 패치가 그 모양이기 때문이다. 그 경우 조상은 트랜잭션 경로에 함께 담겨
 * ([transactionPathsFor]) 기록·복원되고, 담을 수 없으면 [requireRestorableTarget] 이 거부한다.
 */
internal fun Path.hasSymbolicLinkAncestor(path: String): Boolean {
    var ancestor = this
    path.split('/').dropLast(1).forEach { segment ->
        ancestor = ancestor.resolve(segment)
        if (Files.isSymbolicLink(ancestor)) return true
    }
    return false
}

private fun rejectPath(detail: String): Nothing = throw UndineException.StateViolation(detail)
