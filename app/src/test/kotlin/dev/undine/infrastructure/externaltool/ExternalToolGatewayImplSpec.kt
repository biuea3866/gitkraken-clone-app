package dev.undine.infrastructure.externaltool

import dev.undine.domain.ExternalTool
import dev.undine.domain.ExternalToolSettings
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ThemeMode
import dev.undine.domain.WindowBounds
import dev.undine.domain.externaltool.DiffToolInput
import dev.undine.domain.externaltool.DiffToolResult
import dev.undine.domain.externaltool.ExternalToolRunner
import dev.undine.domain.externaltool.ExternalToolUnavailable
import dev.undine.domain.externaltool.MergeToolInput
import dev.undine.domain.externaltool.MergeToolResult
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.initRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.lib.StoredConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val GIT_DIFF = "git-diff"
private const val GIT_MERGE = "git-merge"
private const val APP_DIFF = "app-diff"
private const val APP_MERGE = "app-merge"
private const val FAILURE_EXIT_CODE = 7
private const val RESOLVED_CONTENT = "resolved content"

/**
 * 외부 명령 자체는 가짜 [ExternalToolRunner] 로 대체하되, 설정 해석은 실제 임시 Git 저장소와
 * [GitAccess] 로 검증한다. 개발·CI 에 외부 diff 도구가 없어도 Git 설정 우선순위와 파일 정리를
 * 결정적으로 검증하려는 경계다.
 */
class ExternalToolGatewayImplSpec : FunSpec({

    test("Git diff 설정은 앱 설정보다 먼저 해석하고 경로를 인자 하나씩으로 전달한다") {
        val runner = RecordingRunner()
        val gateway = gateway(
            gitConfig = {
                setString("diff", null, "tool", GIT_DIFF)
                setString("difftool", GIT_DIFF, "cmd", "$GIT_DIFF --left \$LOCAL --right \$REMOTE")
            },
            settings = settingsWith(diffTool = tool(APP_DIFF, "\$LOCAL", "\$REMOTE")),
            runner = runner,
            temporaryDirectoryFactory = ::temporaryDirectoryWithSpecialCharacters,
        )

        gateway.openDiff(DiffToolInput(local = "local", remote = "remote")) shouldBe DiffToolResult.Completed

        val command = runner.commands.single()
        command shouldHaveSize 5
        command[0] shouldBe GIT_DIFF
        command[1] shouldBe "--left"
        command[2].contains("external tool space & $") shouldBe true
        command[3] shouldBe "--right"
        command[4].contains("external tool space & $") shouldBe true
    }

    test("Git diff 설정이 없을 때만 앱 설정 도구를 사용한다") {
        val runner = RecordingRunner()
        val gateway = gateway(
            settings = settingsWith(diffTool = tool(APP_DIFF, "\$LOCAL", "\$REMOTE")),
            runner = runner,
        )

        gateway.openDiff(DiffToolInput(local = "local", remote = "remote")) shouldBe DiffToolResult.Completed

        runner.commands.single().first() shouldBe APP_DIFF
    }

    test("Git 도구 이름만 있고 cmd 템플릿이 없으면 앱 설정으로 내려가지 않는다") {
        val runner = RecordingRunner()
        val settings = CountingSettingsGateway(settingsWith(diffTool = tool(APP_DIFF, "\$LOCAL", "\$REMOTE")))
        val gateway = gateway(
            gitConfig = { setString("diff", null, "tool", GIT_DIFF) },
            settingsGateway = settings,
            runner = runner,
        )

        gateway.openDiff(DiffToolInput(local = "local", remote = "remote")) shouldBe
            ExternalToolUnavailable.MisconfiguredTool(GIT_DIFF, "difftool.$GIT_DIFF.cmd 가 없습니다")

        settings.loadCount shouldBe 0
        runner.commands shouldBe emptyList()
    }

    test("Git 과 앱 설정 모두 없으면 내장 뷰어를 고르지 않고 NoToolConfigured 만 돌려준다") {
        val runner = RecordingRunner()
        val gateway = gateway(settings = settingsWith(), runner = runner)

        gateway.openDiff(DiffToolInput(local = "local", remote = "remote")) shouldBe
            ExternalToolUnavailable.NoToolConfigured

        runner.commands shouldBe emptyList()
    }

    test("설치되지 않은 도구는 프로세스를 시작하기 전에 ToolNotFound 로 구분한다") {
        val runner = RecordingRunner(installed = false)
        val gateway = gateway(
            settings = settingsWith(diffTool = tool(APP_DIFF, "\$LOCAL", "\$REMOTE")),
            runner = runner,
        )

        gateway.openDiff(DiffToolInput(local = "local", remote = "remote")) shouldBe
            ExternalToolUnavailable.ToolNotFound(APP_DIFF)

        runner.commands shouldBe emptyList()
    }

    test("diff 도구의 비정상 종료 코드를 성공으로 뭉개지 않는다") {
        val gateway = gateway(
            settings = settingsWith(diffTool = tool(APP_DIFF, "\$LOCAL", "\$REMOTE")),
            runner = RecordingRunner(exitCode = FAILURE_EXIT_CODE),
        )

        gateway.openDiff(DiffToolInput(local = "local", remote = "remote")) shouldBe
            DiffToolResult.ToolFailed(FAILURE_EXIT_CODE)
    }

    test("설치 확인 뒤 프로세스 시작이 실패해도 ToolNotFound 로 돌려준다") {
        val runner = RecordingRunner { throw IOException("tool disappeared") }
        val gateway = gateway(
            settings = settingsWith(diffTool = tool(APP_DIFF, "\$LOCAL", "\$REMOTE")),
            runner = runner,
        )

        gateway.openDiff(DiffToolInput(local = "local", remote = "remote")) shouldBe
            ExternalToolUnavailable.ToolNotFound(APP_DIFF)
        runner.commands shouldHaveSize 1
    }

    test("merge 도구가 MERGED 파일을 저장하면 변경된 내용을 돌려준다") {
        val runner = RecordingRunner { command ->
            Files.writeString(Path.of(command.last()), RESOLVED_CONTENT)
        }
        val gateway = gateway(
            gitConfig = {
                setString("merge", null, "tool", GIT_MERGE)
                setString("mergetool", GIT_MERGE, "cmd", "$GIT_MERGE \$LOCAL \$REMOTE \$BASE \$MERGED")
            },
            runner = runner,
        )

        gateway.openMerge(MERGE_INPUT) shouldBe MergeToolResult.Resolved(RESOLVED_CONTENT)
    }

    test("merge 도구가 저장 없이 닫으면 원본 대신 Unchanged 를 돌려준다") {
        val gateway = gateway(
            settings = settingsWith(mergeTool = tool(APP_MERGE, "\$LOCAL", "\$REMOTE", "\$BASE", "\$MERGED")),
            runner = RecordingRunner(),
        )

        gateway.openMerge(MERGE_INPUT) shouldBe MergeToolResult.Unchanged
    }

    test("Git merge 설정은 앱 설정보다 먼저 해석하고 네 경로를 인자 하나씩으로 전달한다") {
        val runner = RecordingRunner()
        val gateway = gateway(
            gitConfig = {
                setString("merge", null, "tool", GIT_MERGE)
                setString("mergetool", GIT_MERGE, "cmd", "$GIT_MERGE \$LOCAL \$REMOTE \$BASE \$MERGED")
            },
            settings = settingsWith(mergeTool = tool(APP_MERGE, "\$LOCAL", "\$REMOTE", "\$BASE", "\$MERGED")),
            runner = runner,
            temporaryDirectoryFactory = ::temporaryDirectoryWithSpecialCharacters,
        )

        gateway.openMerge(MERGE_INPUT) shouldBe MergeToolResult.Unchanged

        val command = runner.commands.single()
        command shouldHaveSize 5
        command[0] shouldBe GIT_MERGE
        val paths = command.drop(1)
        paths.forEach { argument -> argument.contains("external tool space & $") shouldBe true }
        paths.map { argument -> Path.of(argument).fileName.toString() } shouldContainExactly
            listOf("LOCAL", "REMOTE", "BASE", "MERGED")
    }

    test("Git merge 설정이 없을 때만 앱 설정 merge 도구를 사용한다") {
        val runner = RecordingRunner()
        val gateway = gateway(
            settings = settingsWith(mergeTool = tool(APP_MERGE, "\$LOCAL", "\$REMOTE", "\$BASE", "\$MERGED")),
            runner = runner,
        )

        gateway.openMerge(MERGE_INPUT) shouldBe MergeToolResult.Unchanged

        runner.commands.single().first() shouldBe APP_MERGE
    }

    test("Git merge 도구 이름만 있고 cmd 템플릿이 없으면 앱 설정으로 내려가지 않는다") {
        val runner = RecordingRunner()
        val settings = CountingSettingsGateway(
            settingsWith(mergeTool = tool(APP_MERGE, "\$LOCAL", "\$REMOTE", "\$BASE", "\$MERGED")),
        )
        val gateway = gateway(
            gitConfig = { setString("merge", null, "tool", GIT_MERGE) },
            settingsGateway = settings,
            runner = runner,
        )

        gateway.openMerge(MERGE_INPUT) shouldBe
            ExternalToolUnavailable.MisconfiguredTool(GIT_MERGE, "mergetool.$GIT_MERGE.cmd 가 없습니다")

        settings.loadCount shouldBe 0
        runner.commands shouldBe emptyList()
    }

    test("Git 과 앱 설정 모두 merge 도구가 없으면 NoToolConfigured 를 돌려준다") {
        val runner = RecordingRunner()
        val gateway = gateway(settings = settingsWith(), runner = runner)

        gateway.openMerge(MERGE_INPUT) shouldBe ExternalToolUnavailable.NoToolConfigured

        runner.commands shouldBe emptyList()
    }

    test("merge 도구에 쓸 수 없는 자리표시자가 있으면 실행하지 않고 설정 오류로 끝낸다") {
        val runner = RecordingRunner()
        val gateway = gateway(
            settings = settingsWith(diffTool = tool(APP_DIFF, "\$LOCAL", "\$MERGED")),
            runner = runner,
        )

        gateway.openDiff(DiffToolInput(local = "local", remote = "remote")) shouldBe
            ExternalToolUnavailable.MisconfiguredTool(APP_DIFF, "\$MERGED 는 이 도구 실행에서 사용할 수 없습니다")

        runner.commands shouldBe emptyList()
    }

    test("비정상 종료 뒤에도 Gateway 가 만든 임시 파일을 정리한다") {
        val runner = RecordingRunner(exitCode = FAILURE_EXIT_CODE)
        val gateway = gateway(
            settings = settingsWith(mergeTool = tool(APP_MERGE, "\$LOCAL", "\$REMOTE", "\$BASE", "\$MERGED")),
            runner = runner,
        )

        gateway.openMerge(MERGE_INPUT) shouldBe MergeToolResult.MergeFailed(FAILURE_EXIT_CODE)

        runner.commands.single().drop(1).forEach { argument -> Files.exists(Path.of(argument)) shouldBe false }
    }

    test("호출자 취소는 삼키지 않고 임시 파일을 정리한 뒤 전파한다") {
        val cancellation = CancellationException("cancel external tool")
        val runner = RecordingRunner { throw cancellation }
        val gateway = gateway(
            settings = settingsWith(diffTool = tool(APP_DIFF, "\$LOCAL", "\$REMOTE")),
            runner = runner,
        )

        shouldThrow<CancellationException> {
            gateway.openDiff(DiffToolInput(local = "local", remote = "remote"))
        } shouldBe cancellation

        runner.commands.single().drop(1).forEach { argument -> Files.exists(Path.of(argument)) shouldBe false }
    }
})

private val MERGE_INPUT = MergeToolInput(
    local = "local",
    remote = "remote",
    base = "base",
    merged = "original merged content",
)

private suspend fun FunSpec.gateway(
    gitConfig: StoredConfig.() -> Unit = {},
    settings: Settings = settingsWith(),
    settingsGateway: SettingsGateway = CountingSettingsGateway(settings),
    runner: ExternalToolRunner,
    temporaryDirectoryFactory: () -> Path = { Files.createTempDirectory("undine-external-tool-") },
): ExternalToolGatewayImpl {
    val repositoryDirectory = tempdir()
    initRepository(repositoryDirectory).use { git ->
        git.repository.config.apply(gitConfig).save()
    }
    val gitAccess = GitAccess()
    gitAccess.open(dev.undine.domain.RepositoryPath(repositoryDirectory.path)) { }
    return ExternalToolGatewayImpl(gitAccess, settingsGateway, runner, temporaryDirectoryFactory)
}

private fun temporaryDirectoryWithSpecialCharacters(): Path =
    Files.createTempDirectory("external tool space & $")

private fun settingsWith(diffTool: ExternalTool? = null, mergeTool: ExternalTool? = null): Settings =
    Settings(
        recentRepositories = emptyList(),
        theme = ThemeMode.SYSTEM,
        window = WindowBounds(width = 1280, height = 800, maximized = false),
        externalTools = ExternalToolSettings(diffTool = diffTool, mergeTool = mergeTool),
    )

private fun tool(executable: String, vararg arguments: String): ExternalTool =
    ExternalTool(executable = executable, arguments = arguments.toList())

private class CountingSettingsGateway(private val settings: Settings) : SettingsGateway {

    var loadCount = 0

    override suspend fun load(): Settings {
        loadCount += 1
        return settings
    }

    override suspend fun save(settings: Settings) = Unit

    override suspend fun update(transform: (Settings) -> Settings) {
        transform(settings)
    }
}

private class RecordingRunner(
    private val installed: Boolean = true,
    private val exitCode: Int = 0,
    private val onRun: suspend (List<String>) -> Unit = {},
) : ExternalToolRunner {

    val commands = mutableListOf<List<String>>()

    override suspend fun isInstalled(executable: String): Boolean = installed

    override suspend fun run(command: List<String>): Int {
        commands += command
        onRun(command)
        return exitCode
    }
}
