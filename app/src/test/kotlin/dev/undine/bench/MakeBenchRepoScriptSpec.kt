package dev.undine.bench

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.util.concurrent.TimeUnit

private const val SCRIPT_RELATIVE_PATH = ".agent/scripts/make-bench-repo.sh"
private const val SCRIPT_TIMEOUT_SECONDS = 120L
private const val MAIN_REF = "refs/heads/main"
private const val BRANCH_REF_PREFIX = "refs/heads/"
private const val BENCH_REF_PREFIX = "refs/heads/bench/"
private const val NEIGHBOUR_NAME = "neighbour.txt"
private const val NEIGHBOUR_CONTENT = "사용자가 두고 간 파일\n"
private const val STAGING_PREFIX = ".make-bench-repo."

/** 받아들이는 가장 작은 커밋 수. 하나만 작아도 병합 지점이 존재할 수 없어 사전 거부된다. */
private const val SMALLEST_COMMITS = "8"
private const val TOO_FEW_COMMITS = "7"

/**
 * seed 를 바꿔도 토폴로지 보장이 유지되는지 본다. `80` 은 1차 검증이 병합 커밋 없이 실패시킨
 * 조합이라 그대로 고정해 둔다 — 되돌아가면 이 테스트가 먼저 깨진다.
 */
private val REGRESSION_SEEDS = listOf("80", "1", "12345", "2147483646")

private val BENCH_REPOSITORY_SCRIPT: File? = generateSequence(File("").absoluteFile) { it.parentFile }
    .map { directory -> File(directory, SCRIPT_RELATIVE_PATH) }
    .firstOrNull { candidate -> candidate.isFile }

/**
 * 벤치 저장소 **생성 스크립트 자체**를 실행해 검증한다 (UND-88).
 *
 * 스크립트는 벤치가 재는 대상을 만드는 도구라 손으로 한 번 돌려 보고 끝내기 쉬운데, 그러면
 * 토폴로지 보장(병합 커밋·미병합 tip)과 실패 시 사용자 파일 보존이 다음 수정에서 조용히
 * 되돌아가도 아무도 모른다. 여기서 **실제로 돌려** 고정한다.
 *
 * 만드는 저장소는 커밋 수십 개짜리다 — 일반 `./gradlew build` 에 대형 저장소 생성 비용을 얹지
 * 않는다. 벤치가 재는 대형 저장소는 여전히 사람이 따로 만든다.
 *
 * `bash`·`git` 이 없는 환경에서는 건너뛴다.
 */
class MakeBenchRepoScriptSpec : FunSpec({

    val scriptAvailable = supportsBenchRepositoryScript()

    test("가장 작은 유효 입력에서도 seed 와 무관하게 병합 커밋과 미병합 tip 이 함께 생긴다")
        .config(enabled = scriptAvailable) {
            REGRESSION_SEEDS.forEach { seed ->
                val output = File(tempdir(), "repository")

                val run = runBenchRepositoryScript(
                    listOf(
                        "--commits", SMALLEST_COMMITS,
                        "--branches", "1",
                        "--seed", seed,
                        "--output", output.path,
                    ),
                )

                withClue("seed=$seed exit=${run.exitCode}\n${run.output}") { run.exitCode shouldBe 0 }
                mergeCommitCount(output) shouldBeGreaterThan 0
                unmergedBenchTips(output).shouldNotBeEmpty()
            }
        }

    test("같은 인자를 서로 다른 빈 경로에 두 번 돌리면 같은 커밋 그래프가 나온다")
        .config(enabled = scriptAvailable) {
            val first = File(tempdir(), "first")
            val second = File(tempdir(), "second")
            val arguments = listOf("--commits", "40", "--branches", "3", "--seed", "7")

            runBenchRepositoryScript(arguments + listOf("--output", first.path)).exitCode shouldBe 0
            runBenchRepositoryScript(arguments + listOf("--output", second.path)).exitCode shouldBe 0

            val snapshot = branchSnapshot(first)
            snapshot shouldBe branchSnapshot(second)
            snapshot.keys.any { name -> name.startsWith(BENCH_REF_PREFIX) } shouldBe true
            mergeCommitCount(first) shouldBeGreaterThan 0
            unmergedBenchTips(first).shouldNotBeEmpty()
        }

    test("병합 지점이 존재할 수 없는 커밋 수는 만들어 보기 전에 거부한다")
        .config(enabled = scriptAvailable) {
            val output = File(tempdir(), "rejected")

            val run = runBenchRepositoryScript(
                listOf("--commits", TOO_FEW_COMMITS, "--branches", "1", "--output", output.path),
            )

            run.exitCode shouldNotBe 0
            // 거부는 아무것도 만들기 전에 끝난다 — 실패한 자리에 반쪽 저장소를 남기지 않는다.
            output.exists() shouldBe false
        }

    // 스크립트는 실패 정리로 재귀 삭제를 한다. 그 대상이 사용자 경로로 새면 되돌릴 수 없다.
    test("fast-import 가 실패하면 출력 경로도 이웃 파일도 건드리지 않는다")
        .config(enabled = scriptAvailable) {
            val parent = tempdir()
            val neighbour = File(parent, NEIGHBOUR_NAME).apply { writeText(NEIGHBOUR_CONTENT) }
            val output = File(parent, "repository").apply { mkdir() }

            val run = runBenchRepositoryScript(
                listOf("--commits", SMALLEST_COMMITS, "--branches", "1", "--output", output.path),
                pathPrefix = gitStubFailingFastImport(tempdir()),
            )

            withClue(run.output) { run.exitCode shouldNotBe 0 }
            neighbour.readText() shouldBe NEIGHBOUR_CONTENT
            // 미리 만들어 둔 빈 출력 디렉터리는 그대로 남아야 한다 — 실패가 지우고 가면 안 된다.
            output.isDirectory shouldBe true
            output.list()?.toList().orEmpty().shouldBeEmpty()
            // 자기가 만든 staging 은 스스로 치운다.
            parent.list()?.filter { name -> name.startsWith(STAGING_PREFIX) }.orEmpty().shouldBeEmpty()
        }
})

private data class ScriptRun(val exitCode: Int, val output: String)

private fun supportsBenchRepositoryScript(): Boolean =
    BENCH_REPOSITORY_SCRIPT != null && pathEntry("bash") != null && pathEntry("git") != null

private fun pathEntry(program: String): File? =
    System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .map { directory -> File(directory, program) }
        .firstOrNull { candidate -> candidate.canExecute() }

private fun runBenchRepositoryScript(arguments: List<String>, pathPrefix: File? = null): ScriptRun {
    val script = requireNotNull(BENCH_REPOSITORY_SCRIPT) { "$SCRIPT_RELATIVE_PATH 을 찾지 못했습니다" }
    val builder = ProcessBuilder(listOf("bash", script.path) + arguments).redirectErrorStream(true)
    if (pathPrefix != null) {
        val environment = builder.environment()
        environment["PATH"] = pathPrefix.path + File.pathSeparator + environment["PATH"].orEmpty()
    }
    val process = builder.start()
    process.outputStream.close()
    val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
    check(process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "스크립트가 제 시간에 끝나지 않았습니다" }
    return ScriptRun(process.exitValue(), output)
}

/**
 * `fast-import` 만 실패시키고 나머지 하위 명령은 진짜 `git` 에 넘기는 스텁을 [directory] 에 놓는다.
 * 실패를 흉내 내려고 저장소를 망가뜨리지 않는다 — 주입 지점을 한 하위 명령으로 좁힌다.
 */
private fun gitStubFailingFastImport(directory: File): File {
    val realGit = requireNotNull(pathEntry("git")) { "git 을 찾지 못했습니다" }
    val dollar = "$"
    val stub = File(directory, "git")
    stub.writeText(
        """
        #!/usr/bin/env bash
        for argument in "$dollar@"; do
          if [ "$dollar{argument}" = "fast-import" ]; then
            echo "stub: fast-import 실패" >&2
            exit 1
          fi
        done
        exec "${realGit.path}" "$dollar@"
        """.trimIndent() + "\n",
    )
    check(stub.setExecutable(true)) { "스텁에 실행 권한을 주지 못했습니다" }
    return directory
}

private fun <T> withRepository(directory: File, block: (Repository) -> T): T =
    FileRepositoryBuilder()
        .setGitDir(File(directory, ".git"))
        .setMustExist(true)
        .build()
        .use(block)

/** 브랜치 이름 → 커밋 해시. 같은 인자로 만든 두 저장소가 같은 그래프인지 이 값으로 본다. */
private fun branchSnapshot(directory: File): Map<String, String> =
    withRepository(directory) { repository ->
        repository.refDatabase.getRefsByPrefix(BRANCH_REF_PREFIX)
            .associate { reference -> reference.name to reference.objectId.name }
    }

private fun mergeCommitCount(directory: File): Int =
    withRepository(directory) { repository ->
        RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(repository.mainCommitId()))
            walk.count { commit -> commit.parentCount >= 2 }
        }
    }

/** main 에서 도달할 수 없는 bench 브랜치 tip. ref 개수로 세면 이미 병합된 브랜치까지 센다. */
private fun unmergedBenchTips(directory: File): List<String> =
    withRepository(directory) { repository ->
        val reachable = RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(repository.mainCommitId()))
            walk.map { commit -> commit.name }.toSet()
        }
        repository.refDatabase.getRefsByPrefix(BENCH_REF_PREFIX)
            .filterNot { reference -> reference.objectId.name in reachable }
            .map { reference -> reference.name }
    }

private fun Repository.mainCommitId() =
    requireNotNull(resolve(MAIN_REF)) { "$MAIN_REF 를 찾지 못했습니다" }
