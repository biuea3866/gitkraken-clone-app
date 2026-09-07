package dev.undine.packaging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

private const val OTHER_TAG = "v0.0.1"

/**
 * 릴리즈 발행 스크립트를 `gh` 스텁으로 실제 실행해 검증한다 (UND-64).
 *
 * 검증하는 것은 두 계약이다 — **같은 태그로 다시 돌려도 릴리즈가 하나**이고, 발행 조건이
 * 어긋나면 **gh 를 아예 부르지 않는다**(반쪽 릴리즈를 만든 뒤 되돌리지 않는다). 둘 다 워크플로
 * YAML 에 두면 태그를 실제로 밀어야만 확인할 수 있다.
 *
 * 진짜 릴리즈를 만들지 않는다 — `gh` 를 PATH 앞에 놓은 스텁으로 가로채 호출만 기록한다.
 */
class PublishReleaseScriptSpec : FunSpec({

    val available = supportsReleaseScripts()
    // 발행 스크립트는 레포의 gradle.properties 를 SSOT 로 본다 — 테스트도 같은 값을 쓴다.
    val version = if (available) repositoryVersion() else ""
    val tag = "v$version"

    test("릴리즈가 없으면 새로 만들고 세 자산과 checksums.txt 를 함께 올린다").config(enabled = available) {
        val staged = contractAssets(tempdir(), version)
        val gh = ghStub(tempdir(), releaseExists = false)

        val run = runPublish(tag, staged, gh)

        withClue(run.output) { run.exitCode shouldBe 0 }
        val create = gh.invocations().single { line -> line.startsWith("release create") }
        contractAssetNames(version).forEach { name -> create.endsWithArgument(name) shouldBe true }
        gh.invocations().none { line -> line.startsWith("release upload") } shouldBe true
    }

    // 러너 하나가 죽어 워크플로를 다시 돌리는 것은 정상 시나리오다. 그때 릴리즈가 둘이 되면 안 된다.
    // 이미 붙은 자산은 건드리지 않는다 — `--clobber` 는 지운 뒤 올리므로 그 사이 업로드가 실패하면
    // 원본이 사라지고 새 자산 일부만 남는다.
    test("릴리즈가 이미 있으면 만들지 않고 빠진 자산만 올린다").config(enabled = available) {
        val present = EXPECTED_ASSETS.getValue("macos").replace(VERSION, version)
        val staged = contractAssets(tempdir(), version)
        val gh = ghStub(tempdir(), releaseExists = true, existingAssets = listOf(present))

        val run = runPublish(tag, staged, gh)

        withClue(run.output) { run.exitCode shouldBe 0 }
        gh.invocations().none { line -> line.startsWith("release create") } shouldBe true
        val upload = gh.invocations().single { line -> line.startsWith("release upload") }
        withClue(upload) { upload.endsWithArgument(present) shouldBe false }
        contractAssetNames(version).filter { name -> name != present }
            .forEach { name -> withClue(upload) { upload.endsWithArgument(name) shouldBe true } }
    }

    // 지우고 다시 올리는 경로 자체를 만들지 않는다 — 그 경로가 없어야 "지운 뒤 실패" 가 불가능하다.
    test("어떤 재실행에서도 기존 자산을 덮어쓰지 않는다").config(enabled = available) {
        val staged = contractAssets(tempdir(), version)
        val gh = ghStub(tempdir(), releaseExists = true)

        runPublish(tag, staged, gh)

        gh.invocations().none { line -> line.endsWithArgument("--clobber") } shouldBe true
    }

    test("자산이 이미 다 붙어 있으면 업로드를 아예 부르지 않는다").config(enabled = available) {
        val staged = contractAssets(tempdir(), version)
        val gh = ghStub(tempdir(), releaseExists = true, existingAssets = contractAssetNames(version))

        val run = runPublish(tag, staged, gh)

        withClue(run.output) { run.exitCode shouldBe 0 }
        gh.invocations().none { line -> line.startsWith("release upload") } shouldBe true
    }

    // 일부만 붙은 릴리즈를 조용히 성공으로 끝내면 아무도 모른다 — 크게 실패해 사람이 보게 한다.
    test("업로드가 실패하면 비영 종료로 알린다").config(enabled = available) {
        val staged = contractAssets(tempdir(), version)
        val gh = ghStub(tempdir(), releaseExists = true, uploadFails = true)

        val run = runPublish(tag, staged, gh)

        run.exitCode shouldNotBe 0
    }

    test("태그가 undine.version 과 다르면 gh 를 부르기 전에 멈춘다").config(enabled = available) {
        val staged = contractAssets(tempdir(), version)
        val gh = ghStub(tempdir(), releaseExists = false)

        val run = runPublish(OTHER_TAG, staged, gh)

        run.exitCode shouldNotBe 0
        gh.invocations().shouldBeEmpty()
    }

    // 세 잡 중 하나가 실패해 자산이 덜 모인 채로 발행 잡이 돌아도 릴리즈가 생기면 안 된다.
    test("자산이 하나라도 없으면 릴리즈를 만들지 않는다").config(enabled = available) {
        val staged = contractAssets(tempdir(), version, omit = "windows")
        val gh = ghStub(tempdir(), releaseExists = false)

        val run = runPublish(tag, staged, gh)

        run.exitCode shouldNotBe 0
        gh.invocations().shouldBeEmpty()
    }
})

private class GhStub(private val directory: File, private val log: File) {
    val path: File get() = directory

    fun invocations(): List<String> =
        if (log.isFile) log.readLines().filter { line -> line.isNotBlank() } else emptyList()
}

/**
 * `gh` 를 가로채 호출 인자를 [log] 에 적는 스텁. [releaseExists] 가 `release view` 의 종료 코드를
 * 정해 "이미 있는 태그" 와 "새 태그" 두 분기를 재현하고, [existingAssets] 가 `--json assets` 조회에
 * 답해 "이미 붙어 있는 자산" 을 재현한다. [uploadFails] 는 업로드 도중 실패를 재현한다.
 */
private fun ghStub(
    directory: File,
    releaseExists: Boolean,
    existingAssets: List<String> = emptyList(),
    uploadFails: Boolean = false,
): GhStub {
    val log = File(directory, "gh-invocations.log")
    val dollar = "$"
    val assetLines = existingAssets.joinToString(separator = "") { name -> "$name\n" }
    val stub = File(directory, "gh")
    stub.writeText(
        """
        #!/usr/bin/env bash
        echo "$dollar*" >> "${log.path}"
        if [ "$dollar{1-}" = "release" ] && [ "$dollar{2-}" = "view" ]; then
          ${if (releaseExists) ":" else "exit 1"}
          case "$dollar*" in
            *--json*) printf '%s' '$assetLines' ;;
          esac
          exit 0
        fi
        if [ "$dollar{1-}" = "release" ] && [ "$dollar{2-}" = "upload" ]; then
          exit ${if (uploadFails) 1 else 0}
        fi
        exit 0
        """.trimIndent() + "\n",
    )
    check(stub.setExecutable(true)) { "gh 스텁에 실행 권한을 주지 못했습니다" }
    return GhStub(directory, log)
}

private fun runPublish(tag: String, assets: File, gh: GhStub): ScriptRun =
    ReleaseScripts.run(
        requireNotNull(ReleaseScripts.publish) { "publish-release.sh 를 찾지 못했습니다" },
        listOf(tag, assets.path),
        pathPrefix = gh.path,
    )

private fun repositoryVersion(): String =
    ReleaseScripts.run(
        requireNotNull(ReleaseScripts.assets) { "release-assets.sh 를 찾지 못했습니다" },
        listOf("version"),
    ).output.trim()

private fun contractAssetNames(version: String): List<String> =
    EXPECTED_ASSETS.values.map { name -> name.replace(VERSION, version) } + CHECKSUMS_NAME

/** 계약명으로 준비된 자산. [omit] 에 준 OS 토큰 하나는 일부러 빠뜨린다. */
private fun contractAssets(directory: File, version: String, omit: String? = null): File {
    EXPECTED_ASSETS.filterKeys { token -> token != omit }
        .forEach { (token, name) ->
            File(directory, name.replace(VERSION, version)).writeText("$token 자산\n")
        }
    return directory
}

/** 스텁이 적은 인자 줄에 [argument] 가 **하나의 인자로** 들어 있는지 본다. */
private fun String.endsWithArgument(argument: String): Boolean =
    split(" ").any { token -> token == argument || token.endsWith("/$argument") }
