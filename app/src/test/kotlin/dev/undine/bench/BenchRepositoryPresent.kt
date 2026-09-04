package dev.undine.bench

import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.spec.Spec
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * 벤치 저장소 위치를 담는 환경 변수.
 *
 * **시스템 프로퍼티(`-D`)를 쓰지 않는다.** `-D` 는 Gradle 자신의 JVM 에만 붙고 테스트 JVM 으로
 * 가지 않아, 저장소를 만들어 두고도 스펙이 조용히 건너뛰어진다. 환경 변수는 자식 프로세스로
 * 그대로 내려간다.
 */
internal const val BENCH_REPOSITORY_ENV = "UNDINE_BENCH_REPO"

/**
 * 벤치 저장소 경로. 환경 변수가 없거나, 가리키는 곳이 **`GitAccess` 가 실제로 열 수 있는**
 * 워킹트리 저장소가 아니면 null 이다.
 *
 * `.git` 이 있는지만 보지 않는다 — 빈 `.git` 디렉터리나 `HEAD` 가 사라진 gitdir 도 그 검사는
 * 통과하고, 그러면 스펙이 켜진 뒤 `GitAccess.open` 에서 터진다. "저장소가 없어 건너뛴다" 와
 * "저장소가 깨져 실패한다" 는 사용자가 할 일이 다른데, 앞선 판정이 무르면 뒤에서 실패로만 보인다.
 * 실제로 여는 쪽([RepositoryHolder])이 쓰는 것과 같은 JGit 판정을 그대로 쓴다.
 *
 * 경로 문자열 자체가 그 플랫폼에서 경로가 될 수 없으면(`Path.of` 가 던지는 경우) **null** 이다.
 * 여기서 예외가 새면 `@EnabledIf` 평가가 터져, 저장소를 준비하지 않은 일반 `build` 까지
 * 건너뜀이 아니라 실패로 보인다.
 *
 * 판정을 **순수 함수**로 떼어 두는 이유는 그 자체를 테스트하기 위해서다 — 활성화 판정이 틀리면
 * 일반 `build` 가 대형 저장소를 찾다 실패하거나, 반대로 벤치가 아무것도 재지 않은 채 통과한다.
 *
 * [lookup] 은 환경 변수 조회다. 기본값이 실제 경로이며 테스트만 교체한다.
 */
internal fun benchRepositoryPath(lookup: (String) -> String? = { name -> System.getenv(name) }): Path? {
    val configured = lookup(BENCH_REPOSITORY_ENV)?.trim().orEmpty()
    if (configured.isEmpty()) return null
    return runCatching { Path.of(configured) }.getOrNull()?.takeIf { opensAsWorkTreeRepository(it) }
}

/**
 * 실제 열기 경로(`RepositoryHolder#openWorkTreeRepository`)와 **같은 규칙**으로 판정한다.
 *
 * `.git` 이 디렉터리인지만 묻지 않는다 — linked worktree 의 `.git` 은 gitdir 을 가리키는 **파일**이라
 * 그 검사만으로는 거짓이 되고, `GitAccess` 는 멀쩡히 여는 저장소에서 벤치가 조용히 건너뛰어진다.
 * 부모로 거슬러 올라가지 않는 것(ceiling)까지 열기 경로와 같게 두어, 판정과 실제 열기가 갈리지
 * 않게 한다.
 *
 * **베어 저장소는 거짓이다.** JGit 은 베어도 멀쩡히 열지만 `RepositoryHolder` 는 그것을
 * `BARE_REPOSITORY` 로 거절하므로, 열림만 보고 켜면 벤치가 건너뛰지 않고 **실패**한다.
 * 벤치가 재는 것은 워킹트리 저장소의 이력이고, 여기서 열기 계약을 그대로 따라간다.
 */
private fun opensAsWorkTreeRepository(candidate: Path): Boolean {
    val target = candidate.toFile()
    val builder = FileRepositoryBuilder().setMustExist(true)
    target.parentFile?.let(builder::addCeilingDirectory)
    builder.findGitDir(target)
    if (builder.gitDir == null) return false
    return runCatching { builder.build() }
        .map { repository -> repository.use { opened -> !opened.isBare } }
        .getOrDefault(false)
}

/**
 * 벤치 저장소가 준비돼 있을 때만 스펙을 켠다 (`@EnabledIf`).
 *
 * 없으면 **건너뛴다** — 일반 `./gradlew build` 와 CI 에 대형 저장소 생성·측정 비용을 얹지 않는다.
 * 저장소는 `.agent/scripts/make-bench-repo.sh` 가 만든다.
 */
internal class BenchRepositoryPresent : EnabledCondition {

    override fun enabled(kclass: KClass<out Spec>): Boolean = benchRepositoryPath() != null
}
