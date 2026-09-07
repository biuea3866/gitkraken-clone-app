package dev.undine.infrastructure.git.lfs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val SLEEP_MILLIS = 120_000L
private const val UNKNOWN_MODE_EXIT_CODE = 64
private const val FLOOD_CHUNK_BYTES = 8 * 1024

/** 값이 없음을 뜻하는 자리표시자. 인자 개수를 모드마다 고정해 두려고 쓴다. */
private const val NONE = "-"

/**
 * 테스트가 실제로 실행하는 **`git-lfs` 대역**. 시스템에 깔린 `git-lfs` 에도 셸 스크립트에도 기대지
 * 않고 **지금 도는 JVM 자신**을 다시 띄운다 — `ExternalToolTestProcess` 와 같은 방식이다
 * (wave 7 결정 D3: 없다고 실패시키지 않고, 있다고 가정하지도 않는다).
 *
 * CI 에는 `git-lfs` 가 깔려 있을 수 있다. **깔려 있어서 통과하는 테스트는 통과가 아니므로**
 * 이 대역이 그 자리를 대신한다.
 *
 * 인자 배치: `[모드] [모드 전용 인자...] [git-lfs 하위 명령 인자...]` — 실행기가 하위 명령 인자를
 * 뒤에 붙이므로 모드 전용 인자를 앞에 고정 개수로 둔다.
 */
object LfsTestProcess {

    /** 지정한 파일 내용을 stdout·stderr 로 내보내고 주어진 코드로 끝난다. 받은 인자도 기록한다. */
    const val EMIT = "emit"

    /** stdout·stderr 에 각각 지정 바이트를 쏟는다 — 파이프 버퍼가 차도 끝나는지 검증용. */
    const val FLOOD_OUTPUT = "flood-output"

    /** 자기 pid 를 적고 오래 산다 — 취소 종료 검증용. */
    const val SLEEP_FOREVER = "sleep-forever"

    /** 자식 JVM 을 띄우고 자기 pid 를 적은 뒤 오래 산다 — 자손 종료 검증용. */
    const val SPAWN_CHILD = "spawn-child"

    /** 지금 도는 JVM 을 다시 띄우는 실행 파일 경로. */
    fun javaExecutable(): String =
        ProcessHandle.current().info().command()
            .orElseGet { Path.of(System.getProperty("java.home"), "bin", "java").toString() }

    /** [mode] 대역을 실행하는 명령 배열. 첫 원소가 실행 파일이다. */
    fun command(mode: String, vararg arguments: String): List<String> =
        listOf(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            LfsTestProcess::class.java.name,
            mode,
        ) + arguments

    /** stdout 내용·종료 코드를 정해 두고, 실제로 받은 하위 명령 인자를 [argumentLog] 에 남기는 대역. */
    fun emitting(standardOutput: Path?, exitCode: Int, argumentLog: Path?): List<String> =
        command(
            EMIT,
            standardOutput?.toString() ?: NONE,
            exitCode.toString(),
            argumentLog?.toString() ?: NONE,
        )

    @JvmStatic
    fun main(arguments: Array<String>) {
        when (val mode = arguments.first()) {
            EMIT -> emit(arguments)
            FLOOD_OUTPUT -> floodOutput(arguments[1].toInt())
            SLEEP_FOREVER -> sleepForever(Path.of(arguments[1]))
            SPAWN_CHILD -> spawnChild(Path.of(arguments[1]), Path.of(arguments[2]))
            else -> {
                System.err.println("알 수 없는 대역 모드: $mode")
                exitProcess(UNKNOWN_MODE_EXIT_CODE)
            }
        }
    }

    private fun emit(arguments: Array<String>) {
        val standardOutput = arguments[1]
        val exitCode = arguments[2].toInt()
        val argumentLog = arguments[3]
        if (argumentLog != NONE) Files.write(Path.of(argumentLog), arguments.drop(4))
        if (standardOutput != NONE) print(Files.readString(Path.of(standardOutput)))
        System.out.flush()
        exitProcess(exitCode)
    }

    private fun floodOutput(bytes: Int) {
        val chunk = ByteArray(FLOOD_CHUNK_BYTES) { 'x'.code.toByte() }
        var written = 0
        while (written < bytes) {
            val size = minOf(FLOOD_CHUNK_BYTES, bytes - written)
            System.out.write(chunk, 0, size)
            System.err.write(chunk, 0, size)
            written += size
        }
        System.out.flush()
        System.err.flush()
    }

    private fun sleepForever(pidFile: Path) {
        Files.writeString(pidFile, ProcessHandle.current().pid().toString())
        Thread.sleep(SLEEP_MILLIS)
    }

    private fun spawnChild(childPidFile: Path, ownPidFile: Path) {
        ProcessBuilder(command(SLEEP_FOREVER, childPidFile.toString()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Files.writeString(ownPidFile, ProcessHandle.current().pid().toString())
        Thread.sleep(SLEEP_MILLIS)
    }
}
