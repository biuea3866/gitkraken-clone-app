package dev.undine.infrastructure.externaltool

import dev.undine.domain.ExternalTool
import dev.undine.domain.SettingsGateway
import dev.undine.domain.externaltool.DiffToolInput
import dev.undine.domain.externaltool.DiffToolResult
import dev.undine.domain.externaltool.ExternalToolGateway
import dev.undine.domain.externaltool.ExternalToolRunner
import dev.undine.domain.externaltool.ExternalToolUnavailable
import dev.undine.domain.externaltool.MergeToolInput
import dev.undine.domain.externaltool.MergeToolResult
import dev.undine.infrastructure.git.repository.GitAccess
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.lib.Repository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

private const val DIFF_SECTION = "diff"
private const val MERGE_SECTION = "merge"
private const val DIFF_TOOL_SECTION = "difftool"
private const val MERGE_TOOL_SECTION = "mergetool"
private const val TOOL_KEY = "tool"
private const val COMMAND_KEY = "cmd"
private const val LOCAL_PLACEHOLDER = "\$LOCAL"
private const val REMOTE_PLACEHOLDER = "\$REMOTE"
private const val BASE_PLACEHOLDER = "\$BASE"
private const val MERGED_PLACEHOLDER = "\$MERGED"
private const val TEMPORARY_DIRECTORY_PREFIX = "undine-external-tool-"

/**
 * [ExternalToolGateway] 의 구현.
 *
 * Git 설정 읽기는 공유 [GitAccess] 경계 안에서만 수행한다. 설정이 아예 없을 때만 앱 설정을 읽고,
 * 이름만 있는 Git 설정은 잘못된 설정으로 끝낸다 — 사용자가 고른 도구를 다른 앱 설정으로 조용히
 * 바꾸지 않는다. 실제 프로세스 실행은 [ExternalToolRunner] 경계 뒤로 밀어 테스트가 외부 바이너리에
 * 의존하지 않게 한다.
 */
class ExternalToolGatewayImpl(
    private val gitAccess: GitAccess,
    private val settingsGateway: SettingsGateway,
    private val runner: ExternalToolRunner = ProcessExternalToolRunner(),
    private val temporaryDirectoryFactory: () -> Path = { Files.createTempDirectory(TEMPORARY_DIRECTORY_PREFIX) },
) : ExternalToolGateway {

    override suspend fun openDiff(input: DiffToolInput): DiffToolResult = when (val resolved = resolve(ToolKind.DIFF)) {
        is ToolResolution.Unavailable -> resolved.result
        is ToolResolution.Configured -> runDiff(resolved.tool, input)
    }

    override suspend fun isToolAvailable(executable: String): Boolean = runner.isInstalled(executable)

    override suspend fun openMerge(input: MergeToolInput): MergeToolResult =
        when (val resolved = resolve(ToolKind.MERGE)) {
            is ToolResolution.Unavailable -> resolved.result
            is ToolResolution.Configured -> runMerge(resolved.tool, input)
        }

    @Suppress("ReturnCount")
    private suspend fun resolve(kind: ToolKind): ToolResolution {
        when (val gitTool = gitAccess.withRepository { repository -> repository.resolveGitTool(kind) }) {
            GitToolResolution.NotConfigured -> Unit
            is GitToolResolution.Misconfigured -> return ToolResolution.Unavailable(gitTool.result)
            is GitToolResolution.Configured -> return ToolResolution.Configured(gitTool.tool)
        }

        val appTool = settingsGateway.load().externalTools.toolFor(kind)
            ?: return ToolResolution.Unavailable(ExternalToolUnavailable.NoToolConfigured)
        return appTool.toResolution(source = "앱 설정")
    }

    private suspend fun runDiff(tool: ExternalTool, input: DiffToolInput): DiffToolResult {
        if (!runner.isInstalled(tool.executable)) return ExternalToolUnavailable.ToolNotFound(tool.executable)

        val files = TemporaryToolFiles.create(temporaryDirectoryFactory) { directory ->
            ToolFilePaths(
                local = directory.resolve("LOCAL"),
                remote = directory.resolve("REMOTE"),
            ).also { paths ->
                Files.writeString(paths.local, input.local)
                Files.writeString(paths.remote, input.remote)
            }
        }
        return files.using { paths ->
            when (val command = tool.commandFor(paths)) {
                is CommandResolution.Misconfigured -> command.result
                is CommandResolution.Ready ->
                    try {
                        val exitCode = runner.run(command.command)
                        if (exitCode == 0) DiffToolResult.Completed else DiffToolResult.ToolFailed(exitCode)
                    } catch (failure: IOException) {
                        toolNotFoundAfterStartFailure(tool.executable, failure)
                    }
            }
        }
    }

    private suspend fun runMerge(tool: ExternalTool, input: MergeToolInput): MergeToolResult {
        if (!runner.isInstalled(tool.executable)) return ExternalToolUnavailable.ToolNotFound(tool.executable)

        val files = TemporaryToolFiles.create(temporaryDirectoryFactory) { directory ->
            ToolFilePaths(
                local = directory.resolve("LOCAL"),
                remote = directory.resolve("REMOTE"),
                base = directory.resolve("BASE"),
                merged = directory.resolve("MERGED"),
            ).also { paths ->
                Files.writeString(paths.local, input.local)
                Files.writeString(paths.remote, input.remote)
                Files.writeString(requireNotNull(paths.base), input.base)
                Files.writeString(requireNotNull(paths.merged), input.merged)
            }
        }
        return files.using { paths ->
            when (val command = tool.commandFor(paths)) {
                is CommandResolution.Misconfigured -> command.result
                is CommandResolution.Ready -> {
                    val exitCode = try {
                        runner.run(command.command)
                    } catch (failure: IOException) {
                        return@using toolNotFoundAfterStartFailure(tool.executable, failure)
                    }
                    if (exitCode != 0) return@using MergeToolResult.MergeFailed(exitCode)

                    val merged = requireNotNull(paths.merged)
                    val content = Files.readString(merged)
                    if (content == input.merged) MergeToolResult.Unchanged else MergeToolResult.Resolved(content)
                }
            }
        }
    }
}

private enum class ToolKind(
    val settingSection: String,
    val toolSection: String,
) {
    DIFF(DIFF_SECTION, DIFF_TOOL_SECTION),
    MERGE(MERGE_SECTION, MERGE_TOOL_SECTION),
}

private sealed interface ToolResolution {

    data class Configured(val tool: ExternalTool) : ToolResolution

    data class Unavailable(val result: ExternalToolUnavailable) : ToolResolution
}

private sealed interface GitToolResolution {

    data object NotConfigured : GitToolResolution

    data class Configured(val tool: ExternalTool) : GitToolResolution

    data class Misconfigured(val result: ExternalToolUnavailable.MisconfiguredTool) : GitToolResolution
}

@Suppress("ReturnCount")
private fun Repository.resolveGitTool(kind: ToolKind): GitToolResolution {
    val toolName = config.getString(kind.settingSection, null, TOOL_KEY)
        ?: return GitToolResolution.NotConfigured
    if (toolName.isBlank()) {
        return GitToolResolution.Misconfigured(
            ExternalToolUnavailable.MisconfiguredTool(toolName, "${kind.settingSection}.tool 이름이 비어 있습니다"),
        )
    }
    val template = config.getString(kind.toolSection, toolName, COMMAND_KEY)
        ?: return missingCommand(kind, toolName)
    if (template.isBlank()) return missingCommand(kind, toolName)

    val arguments = try {
        parseCommandTemplate(template)
    } catch (failure: IllegalArgumentException) {
        return GitToolResolution.Misconfigured(
            ExternalToolUnavailable.MisconfiguredTool(toolName, failure.message.orEmpty()),
        )
    }
    val executable = arguments.firstOrNull()
        ?: return missingCommand(kind, toolName)
    return GitToolResolution.Configured(ExternalTool(executable = executable, arguments = arguments.drop(1)))
}

private fun missingCommand(kind: ToolKind, toolName: String): GitToolResolution.Misconfigured =
    GitToolResolution.Misconfigured(
        ExternalToolUnavailable.MisconfiguredTool(toolName, "${kind.toolSection}.$toolName.cmd 가 없습니다"),
    )

/** 실행 전 확인 뒤의 경쟁 상태도 사용자에게는 같은 설치/실행 불가 상태로 보인다. */
private fun toolNotFoundAfterStartFailure(
    executable: String,
    failure: IOException,
): ExternalToolUnavailable.ToolNotFound {
    System.err.println(
        "[undine] external-tool.process-start-failed executable=$executable type=${failure::class.simpleName}",
    )
    return ExternalToolUnavailable.ToolNotFound(executable)
}

private fun dev.undine.domain.ExternalToolSettings.toolFor(kind: ToolKind): ExternalTool? = when (kind) {
    ToolKind.DIFF -> diffTool
    ToolKind.MERGE -> mergeTool
}

private fun ExternalTool.toResolution(source: String): ToolResolution {
    if (executable.isBlank()) {
        return ToolResolution.Unavailable(
            ExternalToolUnavailable.MisconfiguredTool(source, "실행 파일이 비어 있습니다"),
        )
    }
    return ToolResolution.Configured(this)
}

private sealed interface CommandResolution {

    data class Ready(val command: List<String>) : CommandResolution

    data class Misconfigured(val result: ExternalToolUnavailable.MisconfiguredTool) : CommandResolution
}

private fun ExternalTool.commandFor(paths: ToolFilePaths): CommandResolution {
    val placeholders = mapOf(
        LOCAL_PLACEHOLDER to paths.local.toString(),
        REMOTE_PLACEHOLDER to paths.remote.toString(),
        BASE_PLACEHOLDER to paths.base?.toString(),
        MERGED_PLACEHOLDER to paths.merged?.toString(),
    )
    val unresolved = arguments.firstNotNullOfOrNull { argument ->
        placeholders.entries.firstOrNull { (placeholder, replacement) ->
            placeholder in argument && replacement == null
        }?.key
    }
    if (unresolved != null) {
        return CommandResolution.Misconfigured(
            ExternalToolUnavailable.MisconfiguredTool(executable, "$unresolved 는 이 도구 실행에서 사용할 수 없습니다"),
        )
    }
    return CommandResolution.Ready(
        buildList {
            add(executable)
            arguments.forEach { argument ->
                add(placeholders.entries.fold(argument) { replaced, (placeholder, value) ->
                    value?.let { replaced.replace(placeholder, it) } ?: replaced
                })
            }
        },
    )
}

/** 셸 문법을 평가하지 않는 최소 인자 토크나이저. 인용·공백만 해석하고 제어 연산자는 일반 인자로 둔다. */
@Suppress("CyclomaticComplexMethod")
private fun parseCommandTemplate(template: String): List<String> {
    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var tokenStarted = false
    var index = 0

    while (index < template.length) {
        val character = template[index]
        when {
            quote != null && character == quote -> quote = null
            quote == null && (character == '\'' || character == '"') -> {
                quote = character
                tokenStarted = true
            }

            character == '\\' && index + 1 < template.length &&
                (template[index + 1].isWhitespace() || template[index + 1] == '\\' ||
                    template[index + 1] == '\'' || template[index + 1] == '"') -> {
                current.append(template[++index])
                tokenStarted = true
            }

            quote == null && character.isWhitespace() -> {
                if (tokenStarted) {
                    arguments += current.toString()
                    current.clear()
                    tokenStarted = false
                }
            }

            else -> {
                current.append(character)
                tokenStarted = true
            }
        }
        index += 1
    }
    require(quote == null) { "명령 템플릿의 인용부호가 닫히지 않았습니다" }
    if (tokenStarted) arguments += current.toString()
    require(arguments.isNotEmpty()) { "명령 템플릿이 비어 있습니다" }
    return arguments
}

private data class ToolFilePaths(
    val local: Path,
    val remote: Path,
    val base: Path? = null,
    val merged: Path? = null,
)

private class TemporaryToolFiles private constructor(
    val directory: Path,
    val paths: ToolFilePaths,
) {

    suspend fun <T> using(block: suspend (ToolFilePaths) -> T): T {
        var failure: Exception? = null
        try {
            return block(paths)
        } catch (cancellation: CancellationException) {
            failure = cancellation
            throw cancellation
        } catch (ioFailure: IOException) {
            failure = ioFailure
            throw ioFailure
        } finally {
            delete(failure)
        }
    }

    private fun delete(operationFailure: Exception?) {
        try {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        } catch (cleanupFailure: IOException) {
            operationFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
        }
    }

    companion object {

        fun create(factory: () -> Path, initialize: (Path) -> ToolFilePaths): TemporaryToolFiles {
            val directory = factory()
            return try {
                TemporaryToolFiles(directory, initialize(directory))
            } catch (failure: IOException) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
                throw failure
            }
        }
    }
}
