package dev.undine.packaging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File

internal const val VERSION = "2.3.4"
internal const val MATCHING_TAG = "v2.3.4"
private const val MISMATCHED_TAG = "v9.9.9"

/**
 * major 0 만 걸리는 입력 — `gradle.properties` 도 같은 값이라 **버전 불일치로는 막히지 않는다.**
 * 한 입력이 두 규칙을 동시에 건드리면 어느 쪽이 막았는지 알 수 없다.
 */
private const val ZERO_MAJOR_VERSION = "0.4.2"
private const val ZERO_MAJOR_TAG = "v0.4.2"

/** `<64자리 hex><공백 2개><파일명>` — UND-48 이 이 형식으로 파싱한다. 흔들리면 검증이 조용히 깨진다. */
private val CHECKSUM_LINE = Regex("""^[0-9a-f]{64} {2}\S.*$""")

internal val EXPECTED_ASSETS = mapOf(
    "macos" to "undine-$VERSION-macos.dmg",
    "windows" to "undine-$VERSION-windows.msi",
    "linux" to "undine-$VERSION-linux.deb",
)

/** OS 토큰 → Gradle 이 실제로 내놓는 산출물 이름. 계약명과 다르다는 것이 이 테스트의 전제다. */
private val GRADLE_OUTPUTS = mapOf(
    "macos" to "dmg/Undine-$VERSION.dmg",
    "windows" to "msi/Undine-$VERSION.msi",
    "linux" to "deb/undine_$VERSION-1_amd64.deb",
)

/**
 * 릴리즈 자산 계약 스크립트를 **실제로 실행해** 검증한다 (UND-64).
 *
 * 이 판단들이 워크플로 YAML 안에 있으면 태그를 밀어야만 돌아서 아무도 검증하지 못한다.
 * 태그↔버전 대조·자산명·체크섬 형식이 어긋나면 자동 업데이트(UND-48)가 **조용히** 아무것도
 * 찾지 못하거나 잘못된 버전을 설치한다. 여기서 고정한다.
 *
 * `bash` 나 체크섬 도구가 없는 환경에서는 건너뛴다.
 */
class ReleaseAssetsScriptSpec : FunSpec({

    val available = supportsReleaseScripts()

    test("태그가 undine.version 과 같으면 통과하고 버전을 알려준다").config(enabled = available) {
        val run = runAssets(propertiesWith(tempdir(), VERSION), listOf("check-tag", MATCHING_TAG))

        withClue(run.output) { run.exitCode shouldBe 0 }
        run.output.trim() shouldBe VERSION
    }

    // 태그와 undine.version 이 **같아도** 막혀야 한다. macOS jpackage 가 major 0 을 거부하므로
    // 통과시키면 3종 빌드를 다 돌린 뒤 macOS 잡에서 실패한다.
    test("undine.version 과 같더라도 MAJOR 가 0 인 태그는 거부한다").config(enabled = available) {
        val run = runAssets(
            propertiesWith(tempdir(), ZERO_MAJOR_VERSION),
            listOf("check-tag", ZERO_MAJOR_TAG),
        )

        run.exitCode shouldNotBe 0
        run.output shouldContain "jpackage"
    }

    test("태그가 undine.version 과 다르면 비영 종료로 발행 준비를 멈춘다").config(enabled = available) {
        val run = runAssets(propertiesWith(tempdir(), VERSION), listOf("check-tag", MISMATCHED_TAG))

        run.exitCode shouldNotBe 0
        run.output shouldContain MISMATCHED_TAG
    }

    test("vX.Y.Z 형식이 아닌 태그도 거부한다").config(enabled = available) {
        runAssets(propertiesWith(tempdir(), VERSION), listOf("check-tag", VERSION)).exitCode shouldNotBe 0
    }

    test("OS 별 자산명이 계약 규칙을 따른다").config(enabled = available) {
        val properties = propertiesWith(tempdir(), VERSION)

        EXPECTED_ASSETS.forEach { (token, expected) ->
            val run = runAssets(properties, listOf("asset-name", token))

            withClue("$token: ${run.output}") { run.exitCode shouldBe 0 }
            run.output.trim() shouldBe expected
        }
    }

    // 러너 이름(ubuntu-latest)이 아니라 계약 토큰(linux)만 받는다 — 흔들리면 자산명이 흔들린다.
    test("계약 토큰이 아닌 OS 이름은 자산명을 만들어 주지 않는다").config(enabled = available) {
        runAssets(propertiesWith(tempdir(), VERSION), listOf("asset-name", "ubuntu")).exitCode shouldNotBe 0
    }

    // Gradle 산출물 이름은 계약명과 다르다. 그 간극을 스크립트가 메우지 않으면 클라이언트가 못 찾는다.
    test("Gradle 산출물 이름과 무관하게 계약명으로 준비한다").config(enabled = available) {
        val properties = propertiesWith(tempdir(), VERSION)
        val source = gradleOutputs(tempdir())
        val staged = File(tempdir(), "staged")

        EXPECTED_ASSETS.forEach { (token, expected) ->
            val run = runAssets(properties, listOf("stage", token, source.path, staged.path))

            withClue("$token: ${run.output}") { run.exitCode shouldBe 0 }
            File(staged, expected).readText() shouldBe "$token 산출물\n"
        }
    }

    // 붙일 산출물이 없다. 추측으로 아무거나 고르면 클라이언트가 엉뚱한 파일을 받는다.
    test("대상 확장자 산출물이 없으면 계약 자산을 만들지 않고 멈춘다").config(enabled = available) {
        val source = File(tempdir(), "empty").apply { mkdirs() }
        val staged = File(tempdir(), "staged")

        val run = runAssets(propertiesWith(tempdir(), VERSION), listOf("stage", "macos", source.path, staged.path))

        run.exitCode shouldNotBe 0
        File(staged, EXPECTED_ASSETS.getValue("macos")).exists() shouldBe false
    }

    // 둘 중 무엇을 발행할지 알 수 없다 — 하나를 골라 올리면 어느 쪽이 나갔는지 아무도 모른다.
    test("대상 확장자 산출물이 여럿이면 계약 자산을 만들지 않고 멈춘다").config(enabled = available) {
        val source = tempdir()
        listOf("dmg/Undine-$VERSION.dmg", "dmg/Undine-$VERSION-debug.dmg").forEach { relativePath ->
            File(source, relativePath).apply { parentFile.mkdirs() }.writeText("macos 산출물\n")
        }
        val staged = File(tempdir(), "staged")

        val run = runAssets(propertiesWith(tempdir(), VERSION), listOf("stage", "macos", source.path, staged.path))

        run.exitCode shouldNotBe 0
        File(staged, EXPECTED_ASSETS.getValue("macos")).exists() shouldBe false
    }

    test("세 자산이 다 모이기 전에는 checksums.txt 를 만들지 않는다").config(enabled = available) {
        val staged = stagedAssets(tempdir(), omit = "linux")

        val run = runAssets(propertiesWith(tempdir(), VERSION), listOf("checksums", staged.path))

        run.exitCode shouldNotBe 0
        run.output shouldContain EXPECTED_ASSETS.getValue("linux")
        File(staged, CHECKSUMS_NAME).exists() shouldBe false
    }

    test("checksums.txt 가 세 자산을 계약 형식의 줄로 담는다").config(enabled = available) {
        val staged = stagedAssets(tempdir())

        val run = runAssets(propertiesWith(tempdir(), VERSION), listOf("checksums", staged.path))

        withClue(run.output) { run.exitCode shouldBe 0 }
        val lines = File(staged, CHECKSUMS_NAME).readLines().filter { line -> line.isNotBlank() }
        lines.forEach { line -> withClue(line) { CHECKSUM_LINE.matches(line) shouldBe true } }
        lines.map { line -> line.substringAfter("  ") } shouldBe EXPECTED_ASSETS.values.toList()
    }

    test("체크섬은 준비된 자산과 일치하고, 파일이 바뀌면 검증이 실패한다").config(enabled = available) {
        val properties = propertiesWith(tempdir(), VERSION)
        val staged = stagedAssets(tempdir())
        runAssets(properties, listOf("checksums", staged.path)).exitCode shouldBe 0

        val intact = runAssets(properties, listOf("verify-checksums", staged.path))
        withClue(intact.output) { intact.exitCode shouldBe 0 }

        File(staged, EXPECTED_ASSETS.getValue("macos")).appendText("변조\n")
        val tampered = runAssets(properties, listOf("verify-checksums", staged.path))

        tampered.exitCode shouldNotBe 0
    }
})

internal const val CHECKSUMS_NAME = "checksums.txt"

internal fun runAssets(properties: File, arguments: List<String>): ScriptRun =
    ReleaseScripts.run(
        requireNotNull(ReleaseScripts.assets) { "release-assets.sh 를 찾지 못했습니다" },
        listOf("--properties", properties.path) + arguments,
    )

/** OS 토큰별로 Gradle 이 내놓는 이름 그대로 가짜 산출물을 만든다. */
private fun gradleOutputs(directory: File): File {
    GRADLE_OUTPUTS.forEach { (token, relativePath) ->
        val artifact = File(directory, relativePath)
        artifact.parentFile.mkdirs()
        artifact.writeText("$token 산출물\n")
    }
    return directory
}

/** 계약명으로 준비된 자산 디렉터리. [omit] 에 준 OS 토큰 하나는 일부러 빠뜨린다. */
internal fun stagedAssets(directory: File, omit: String? = null): File {
    EXPECTED_ASSETS.filterKeys { token -> token != omit }
        .forEach { (token, name) -> File(directory, name).writeText("$token 자산\n") }
    return directory
}

internal fun propertiesWith(directory: File, version: String): File =
    File(directory, "gradle.properties").apply { writeText("undine.jvm=21\nundine.version=$version\n") }
