package dev.undine.infrastructure.git.lfs

import dev.undine.domain.lfs.LfsCommandOutcome
import dev.undine.domain.lfs.LfsCommandRunner
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Path

/**
 * 실행된 명령을 기록하는 CLI 대역.
 *
 * 프로세스 경계 자체는 [ProcessLfsCommandRunnerSpec] 이 실제 프로세스로 검증한다. 여기서는 그
 * 위층 — **어떤 하위 명령이 실행되는가**, 크기 조회가 수신을 시작하지 않는가, 결과 해석이
 * 맞는가 — 를 본다.
 *
 * @param outcomes 인자 목록별 결과. 없으면 [fallback] 을 돌려준다.
 */
internal class RecordingLfsCommandRunner(
    private val outcomes: Map<List<String>, LfsCommandOutcome> = emptyMap(),
    private val fallback: LfsCommandOutcome = LfsCommandOutcome.Completed(0, "", ""),
    private val progressLines: List<String> = emptyList(),
    private val blockUntil: CompletableDeferred<Unit>? = null,
) : LfsCommandRunner {

    val executed = mutableListOf<List<String>>()
    val workingDirectories = mutableListOf<Path>()

    override suspend fun run(
        arguments: List<String>,
        workingDirectory: Path,
        onOutputLine: (String) -> Unit,
    ): LfsCommandOutcome {
        executed.add(arguments)
        workingDirectories.add(workingDirectory)
        progressLines.forEach(onOutputLine)
        blockUntil?.await()
        return outcomes[arguments] ?: fallback
    }
}
