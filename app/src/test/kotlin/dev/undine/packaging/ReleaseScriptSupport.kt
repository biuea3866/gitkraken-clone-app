package dev.undine.packaging

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 릴리즈 계약 스크립트를 **실제로 실행**하기 위한 공통 도구 (UND-64).
 *
 * `MakeBenchRepoScriptSpec` 이 세운 선례를 그대로 따른다 — 스크립트 로직을 Kotlin 으로 옮겨
 * 흉내 내지 않고, `packaging/` 의 그 파일을 `tempdir()` 에서 돌려 본다. 워크플로가 부르는 것과
 * 검증하는 것이 같은 파일이어야 검증에 의미가 있다.
 */
internal object ReleaseScripts {

    private const val TIMEOUT_SECONDS = 60L

    val assets: File? = locate("packaging/release-assets.sh")
    val publish: File? = locate("packaging/publish-release.sh")

    /** 테스트 작업 디렉터리(`app/`)에서 위로 올라가며 레포 루트의 스크립트를 찾는다. */
    private fun locate(relativePath: String): File? =
        generateSequence(File("").absoluteFile) { directory -> directory.parentFile }
            .map { directory -> File(directory, relativePath) }
            .firstOrNull { candidate -> candidate.isFile }

    fun run(script: File, arguments: List<String>, pathPrefix: File? = null): ScriptRun {
        val builder = ProcessBuilder(listOf("bash", script.path) + arguments).redirectErrorStream(true)
        if (pathPrefix != null) {
            val environment = builder.environment()
            environment["PATH"] = pathPrefix.path + File.pathSeparator + environment["PATH"].orEmpty()
        }
        val process = builder.start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
        check(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "스크립트가 제 시간에 끝나지 않았습니다" }
        return ScriptRun(process.exitValue(), output)
    }
}

internal data class ScriptRun(val exitCode: Int, val output: String)

internal fun pathEntry(program: String): File? =
    System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .map { directory -> File(directory, program) }
        .firstOrNull { candidate -> candidate.canExecute() }

/** 체크섬 도구는 플랫폼마다 이름이 다르다 — 스크립트가 보는 것과 같은 조건으로 판단한다. */
internal fun supportsReleaseScripts(): Boolean =
    ReleaseScripts.assets != null &&
        ReleaseScripts.publish != null &&
        pathEntry("bash") != null &&
        (pathEntry("shasum") != null || pathEntry("sha256sum") != null)
