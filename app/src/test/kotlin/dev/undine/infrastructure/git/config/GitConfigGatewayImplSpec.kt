package dev.undine.infrastructure.git.config

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.gitconfig.GitConfigKey
import dev.undine.domain.gitconfig.GitConfigSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.io.File

private const val OPERATION = "read git config"

/** 세 범위가 같은 키를 다르게 정했을 때 어느 값이 이기는지 보려면 값이 서로 달라야 한다. */
private const val REPOSITORY_BRANCH = "저장소-기본"
private const val GLOBAL_BRANCH = "전역-기본"
private const val SYSTEM_BRANCH = "시스템-기본"

/**
 * Git 설정 실효값 Gateway — **실제 임시 저장소와 실제 설정 파일**로 검증한다
 * (테스트 규칙 1). JGit 을 Mock 으로 대체하지 않는다.
 *
 * 전역·시스템 설정 파일은 생성자로 주입한다 — `SystemReader.setInstance` 로 JVM 전역 상태를
 * 갈아 끼우면 병렬 실행되는 다른 스펙의 Git 동작까지 바꾼다. 파일은 여전히 진짜 파일이고
 * 파싱도 진짜 JGit 이 한다.
 */
class GitConfigGatewayImplSpec : FunSpec({

    test("저장소 설정에만 값이 있으면 출처가 REPOSITORY 다") {
        val work = tempdir().also(::seedRepository)
        writeConfig(repositoryConfigOf(work), "init", "defaultBranch", REPOSITORY_BRANCH)
        val gateway = gatewayFor(global = emptyConfigFile(tempdir()), system = emptyConfigFile(tempdir()))

        val value = runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }
            .getValue(GitConfigKey.INIT_DEFAULT_BRANCH)

        value.raw shouldBe REPOSITORY_BRANCH
        value.source shouldBe GitConfigSource.REPOSITORY
    }

    test("전역 설정에만 값이 있으면 출처가 GLOBAL 이다") {
        val work = tempdir().also(::seedRepository)
        val global = emptyConfigFile(tempdir()).also { writeConfig(it, "init", "defaultBranch", GLOBAL_BRANCH) }
        val gateway = gatewayFor(global = global, system = emptyConfigFile(tempdir()))

        val value = runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }
            .getValue(GitConfigKey.INIT_DEFAULT_BRANCH)

        value.raw shouldBe GLOBAL_BRANCH
        value.source shouldBe GitConfigSource.GLOBAL
    }

    test("시스템 설정에만 값이 있으면 출처가 SYSTEM 이다") {
        val work = tempdir().also(::seedRepository)
        val system = emptyConfigFile(tempdir()).also { writeConfig(it, "init", "defaultBranch", SYSTEM_BRANCH) }
        val gateway = gatewayFor(global = emptyConfigFile(tempdir()), system = system)

        val value = runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }
            .getValue(GitConfigKey.INIT_DEFAULT_BRANCH)

        value.raw shouldBe SYSTEM_BRANCH
        value.source shouldBe GitConfigSource.SYSTEM
    }

    test("세 범위에 모두 있으면 저장소 값이 이기고 그 범위가 출처로 온다") {
        val work = tempdir().also(::seedRepository)
        writeConfig(repositoryConfigOf(work), "init", "defaultBranch", REPOSITORY_BRANCH)
        val global = emptyConfigFile(tempdir()).also { writeConfig(it, "init", "defaultBranch", GLOBAL_BRANCH) }
        val system = emptyConfigFile(tempdir()).also { writeConfig(it, "init", "defaultBranch", SYSTEM_BRANCH) }
        val gateway = gatewayFor(global = global, system = system)

        val value = runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }
            .getValue(GitConfigKey.INIT_DEFAULT_BRANCH)

        value.raw shouldBe REPOSITORY_BRANCH
        value.source shouldBe GitConfigSource.REPOSITORY
    }

    test("전역과 시스템에만 있으면 전역이 시스템을 이긴다") {
        val work = tempdir().also(::seedRepository)
        val global = emptyConfigFile(tempdir()).also { writeConfig(it, "init", "defaultBranch", GLOBAL_BRANCH) }
        val system = emptyConfigFile(tempdir()).also { writeConfig(it, "init", "defaultBranch", SYSTEM_BRANCH) }
        val gateway = gatewayFor(global = global, system = system)

        val value = runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }
            .getValue(GitConfigKey.INIT_DEFAULT_BRANCH)

        value.raw shouldBe GLOBAL_BRANCH
        value.source shouldBe GitConfigSource.GLOBAL
    }

    test("저장소의 빈 값은 설정된 값이라 전역 값을 가린다") {
        val work = tempdir().also(::seedRepository)
        writeEmptyConfig(repositoryConfigOf(work), "init", "defaultBranch")
        val global = emptyConfigFile(tempdir()).also { writeConfig(it, "init", "defaultBranch", GLOBAL_BRANCH) }
        val gateway = gatewayFor(global = global, system = emptyConfigFile(tempdir()))

        val value = runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }
            .getValue(GitConfigKey.INIT_DEFAULT_BRANCH)

        value.raw shouldBe ""
        value.source shouldBe GitConfigSource.REPOSITORY
    }

    test("전역의 빈 값도 시스템 값을 가린다") {
        val global = emptyConfigFile(tempdir()).also { writeEmptyConfig(it, "init", "defaultBranch") }
        val system = emptyConfigFile(tempdir()).also { writeConfig(it, "init", "defaultBranch", SYSTEM_BRANCH) }
        val gateway = gatewayFor(global = global, system = system)

        val value = runBlocking { gateway.effectiveValues(null) }
            .getValue(GitConfigKey.INIT_DEFAULT_BRANCH)

        value.raw shouldBe ""
        value.source shouldBe GitConfigSource.GLOBAL
    }

    test("따옴표로 감싼 값의 앞뒤 공백을 그대로 싣는다") {
        // 사용자가 따옴표까지 써서 남긴 공백은 의도된 값이다 — raw 를 다듬으면 다른 값이 된다.
        val global = emptyConfigFile(tempdir()).also { it.appendText("[user]\n\tname = \"  전역 사용자  \"\n") }
        val gateway = gatewayFor(global = global, system = emptyConfigFile(tempdir()))

        val value = runBlocking { gateway.effectiveValues(null) }.getValue(GitConfigKey.USER_NAME)

        value.raw shouldBe "  전역 사용자  "
        value.source shouldBe GitConfigSource.GLOBAL
    }

    test("공백만 있는 값도 부재가 아니라 그 범위의 값이다") {
        val global = emptyConfigFile(tempdir()).also { it.appendText("[diff]\n\ttool = \"   \"\n") }
        val system = emptyConfigFile(tempdir()).also { writeConfig(it, "diff", "tool", "meld") }
        val gateway = gatewayFor(global = global, system = system)

        val value = runBlocking { gateway.effectiveValues(null) }.getValue(GitConfigKey.DIFF_TOOL)

        value.raw shouldBe "   "
        value.source shouldBe GitConfigSource.GLOBAL
    }

    test("저장소가 열려 있지 않아도 전역·시스템 값을 조회한다") {
        val global = emptyConfigFile(tempdir()).also { writeConfig(it, "user", "name", "전역 사용자") }
        val system = emptyConfigFile(tempdir()).also { writeConfig(it, "diff", "tool", "meld") }
        val gateway = gatewayFor(global = global, system = system)

        // 저장소 없이 부르는 경로다 — GitAccess.withRepository 의 StateViolation 을 타지 않는다.
        val values = runBlocking { gateway.effectiveValues(null) }

        values.getValue(GitConfigKey.USER_NAME).source shouldBe GitConfigSource.GLOBAL
        values.getValue(GitConfigKey.USER_NAME).raw shouldBe "전역 사용자"
        values.getValue(GitConfigKey.DIFF_TOOL).source shouldBe GitConfigSource.SYSTEM
    }

    test("세 범위 어디에도 키가 없으면 부재다 — 앱 설정으로 대체하지 않는다") {
        val work = tempdir().also(::seedRepository)
        val gateway = gatewayFor(global = emptyConfigFile(tempdir()), system = emptyConfigFile(tempdir()))

        val values = runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }

        // seedRepository 는 커밋용 user.name·user.email 만 심는다.
        values.shouldNotContainKey(GitConfigKey.INIT_DEFAULT_BRANCH)
        values.shouldNotContainKey(GitConfigKey.COMMIT_GPGSIGN)
        values.shouldNotContainKey(GitConfigKey.USER_SIGNING_KEY)
    }

    test("설정 파일이 아예 없어도 실패가 아니라 부재다") {
        val missing = File(tempdir(), "없는-설정")
        val gateway = gatewayFor(global = missing, system = missing)

        runBlocking { gateway.effectiveValues(null) }.shouldBeEmpty()
    }

    test("아홉 개 키를 모두 읽고 Boolean 키는 domain 이 Git 철자를 해석한다") {
        val work = tempdir().also(::seedRepository)
        val repositoryConfig = repositoryConfigOf(work)
        writeConfig(repositoryConfig, "init", "defaultBranch", "main")
        writeConfig(repositoryConfig, "pull", "rebase", "true")
        writeConfig(repositoryConfig, "diff", "tool", "meld")
        writeConfig(repositoryConfig, "merge", "tool", "kdiff3")
        writeConfig(repositoryConfig, "commit", "gpgsign", "yes")
        writeConfig(repositoryConfig, "gpg", "format", "ssh")
        writeConfig(repositoryConfig, "user", "signingkey", "ABCD1234")
        val gateway = gatewayFor(global = emptyConfigFile(tempdir()), system = emptyConfigFile(tempdir()))

        val values = runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }

        values.keys shouldBe GitConfigKey.entries.toSet()
        values.getValue(GitConfigKey.COMMIT_GPGSIGN).asBoolean() shouldBe true
        values.getValue(GitConfigKey.PULL_REBASE).asBoolean() shouldBe true
        values.getValue(GitConfigKey.GPG_FORMAT).raw shouldBe "ssh"
    }

    test("조회는 읽기 전용이라 어떤 설정 파일도 바뀌지 않는다") {
        val work = tempdir().also(::seedRepository)
        val repositoryConfig = repositoryConfigOf(work)
        writeConfig(repositoryConfig, "init", "defaultBranch", REPOSITORY_BRANCH)
        val global = emptyConfigFile(tempdir()).also { writeConfig(it, "user", "name", "전역 사용자") }
        val system = emptyConfigFile(tempdir()).also { writeConfig(it, "merge", "tool", "kdiff3") }
        val before = listOf(repositoryConfig, global, system).map(File::readText)

        runBlocking { gatewayFor(global, system).effectiveValues(RepositoryPath(work.absolutePath)) }

        listOf(repositoryConfig, global, system).map(File::readText) shouldBe before
    }

    test("전역 설정이 손상되면 부재가 아니라 GitOperationFailed 로 전파한다") {
        val corrupted = emptyConfigFile(tempdir()).also { it.writeText("[user\n\tname = 깨진 설정\n") }
        val gateway = gatewayFor(global = corrupted, system = emptyConfigFile(tempdir()))

        val thrown = shouldThrow<UndineException.GitOperationFailed> {
            runBlocking { gateway.effectiveValues(null) }
        }

        thrown.operation shouldBe OPERATION
        // 원인을 잃으면 화면이 "무엇이 깨졌는지" 를 로그로도 말할 수 없다.
        thrown.cause.shouldNotBeNull()
    }

    test("시스템 설정이 손상되면 부재가 아니라 GitOperationFailed 로 전파한다") {
        val corrupted = emptyConfigFile(tempdir()).also { it.writeText("[merge\n\ttool = 깨진 설정\n") }
        val gateway = gatewayFor(global = emptyConfigFile(tempdir()), system = corrupted)

        shouldThrow<UndineException.GitOperationFailed> {
            runBlocking { gateway.effectiveValues(null) }
        }.operation shouldBe OPERATION
    }

    test("저장소 설정이 손상되면 부재가 아니라 GitOperationFailed 로 전파한다") {
        val work = tempdir().also(::seedRepository)
        repositoryConfigOf(work).writeText("[core\n\tbare = 깨진 설정\n")
        val gateway = gatewayFor(global = emptyConfigFile(tempdir()), system = emptyConfigFile(tempdir()))

        shouldThrow<UndineException.GitOperationFailed> {
            runBlocking { gateway.effectiveValues(RepositoryPath(work.absolutePath)) }
        }.operation shouldBe OPERATION
    }

    test("저장소가 아닌 경로를 주면 저장소 범위 없이 전역·시스템만 본다") {
        val notARepository = tempdir()
        val global = emptyConfigFile(tempdir()).also { writeConfig(it, "user", "email", "me@example.com") }
        val gateway = gatewayFor(global = global, system = emptyConfigFile(tempdir()))

        val values = runBlocking { gateway.effectiveValues(RepositoryPath(notARepository.absolutePath)) }

        values.getValue(GitConfigKey.USER_EMAIL).source shouldBe GitConfigSource.GLOBAL
    }
})

private fun gatewayFor(global: File, system: File) =
    GitConfigGatewayImpl(globalConfigFile = global, systemConfigFile = system)

private fun emptyConfigFile(directory: File): File = File(directory, "gitconfig").also { it.writeText("") }

private fun repositoryConfigOf(work: File): File = File(work, ".git/config")

/** 실제 `git config` 파일 문법으로 덧붙인다 — 앱이 읽는 형식과 같은 형식으로 검증한다. */
private fun writeConfig(file: File, section: String, name: String, value: String) {
    file.appendText("[$section]\n\t$name = $value\n")
}

/**
 * 값 없이 키만 적는 Git 표기 (`[init]\n\tdefaultBranch`). `git config` 는 이것을 **설정된 빈 값**으로
 * 읽고 하위 범위를 가린다 — 부재가 아니다 (결정 G36 UND-75).
 */
private fun writeEmptyConfig(file: File, section: String, name: String) {
    file.appendText("[$section]\n\t$name\n")
}

private fun seedRepository(work: File) {
    Git.init().setDirectory(work).setInitialBranch("main").call().use { git ->
        git.repository.config.apply {
            setString("user", null, "name", "테스터")
            setString("user", null, "email", "tester@example.com")
            save()
        }
    }
}
