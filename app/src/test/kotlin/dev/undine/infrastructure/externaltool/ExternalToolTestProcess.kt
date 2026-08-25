package dev.undine.infrastructure.externaltool

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val SLEEP_MILLIS = 120_000L
private const val UNKNOWN_MODE_EXIT_CODE = 64
private const val FLOOD_CHUNK_BYTES = 8 * 1024

/**
 * 테스트가 실제로 실행하는 **외부 도구 대역**. 시스템에 깔린 diff/merge 도구에도, 셸 스크립트에도
 * 기대지 않고 **지금 도는 JVM 자신**을 다시 띄운다 (wave 7 결정 D3 — 없다고 실패시키지 않고,
 * 있다고 가정하지도 않는다).
 *
 * 셸 스크립트 픽스처는 POSIX 실행 권한을 다루는 파일 시스템에서만 만들 수 있어 그 밖의 플랫폼에서는
 * 실제 프로세스 경계가 통째로 검증되지 않았다. JVM 만으로 도는 이 대역은 지원 플랫폼 전부에서 돈다.
 */
object ExternalToolTestProcess {

    /** 첫 인자를 출력 파일로 삼아 나머지 인자를 한 줄씩 적는다 — 인자 보존 검증용. */
    const val ECHO_ARGUMENTS = "echo-arguments"

    /** 주어진 코드로 곧장 종료한다 — 비정상 종료 코드 전달 검증용. */
    const val EXIT_WITH = "exit-with"

    /** stdout·stderr 에 각각 지정 바이트를 쏟는다 — 파이프 버퍼가 차도 끝나는지 검증용. */
    const val FLOOD_OUTPUT = "flood-output"

    /** 자기 pid 를 적고 오래 산다 — 시간 제한 없음·취소 종료 검증용. */
    const val SLEEP_FOREVER = "sleep-forever"

    /** 자식 JVM 을 띄우고 자기 pid 를 적은 뒤 오래 산다 — 자손 종료 검증용. */
    const val SPAWN_CHILD = "spawn-child"

    /** 지금 도는 JVM 을 다시 띄우는 실행 파일 경로. 플랫폼별 확장자 판단을 런타임에 맡긴다. */
    fun javaExecutable(): String =
        ProcessHandle.current().info().command()
            .orElseGet { Path.of(System.getProperty("java.home"), "bin", "java").toString() }

    /** [mode] 대역을 실행하는 명령 배열. 첫 원소가 실행 파일이라는 [ExternalToolRunner] 계약을 따른다. */
    fun command(mode: String, vararg arguments: String): List<String> =
        listOf(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            ExternalToolTestProcess::class.java.name,
            mode,
        ) + arguments

    @JvmStatic
    fun main(arguments: Array<String>) {
        when (val mode = arguments.first()) {
            ECHO_ARGUMENTS -> echoArguments(Path.of(arguments[1]), arguments.drop(2))
            EXIT_WITH -> exitProcess(arguments[1].toInt())
            FLOOD_OUTPUT -> floodOutput(arguments[1].toInt())
            SLEEP_FOREVER -> sleepForever(Path.of(arguments[1]))
            SPAWN_CHILD -> spawnChild(Path.of(arguments[1]), Path.of(arguments[2]))
            else -> {
                System.err.println("알 수 없는 대역 모드: $mode")
                exitProcess(UNKNOWN_MODE_EXIT_CODE)
            }
        }
    }

    private fun echoArguments(output: Path, arguments: List<String>) {
        Files.write(output, arguments)
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
