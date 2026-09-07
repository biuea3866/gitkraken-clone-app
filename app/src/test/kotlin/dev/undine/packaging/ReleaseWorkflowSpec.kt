package dev.undine.packaging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

private const val WORKFLOW_PATH = "../.github/workflows/release.yml"
private const val CONTRACT_PATH = "../packaging/RELEASE-CONTRACT.md"

/** 조회 좌표. 계약 문서에만 적고 워크플로·스크립트에는 박지 않는다 — 포크가 엉뚱한 곳을 본다. */
private const val REPOSITORY_COORDINATE = "biuea3866/gitkraken-clone-app"

private val PACKAGING_SCRIPTS = listOf("release-assets.sh", "publish-release.sh")

/**
 * 릴리즈 워크플로 배선과 발행 계약 문서를 정적으로 점검한다 (UND-64).
 *
 * 워크플로는 태그를 밀어야만 돌아서 실행으로 검증할 수 없다. 대신 **검증 밖으로 새면 안 되는
 * 것들**을 여기서 고정한다 — 세 OS 잡, 세 잡이 끝난 뒤에야 발행하는 게이트, 버전·좌표를
 * 하드코딩하지 않는 규율, 그리고 UND-48 이 이 문서만 읽고 구현할 수 있는지.
 */
class ReleaseWorkflowSpec : FunSpec({

    val workflow = { File(WORKFLOW_PATH).readText() }
    val contract = { File(CONTRACT_PATH).readText() }

    test("태그 push 로만 도는 릴리즈 워크플로가 있다") {
        File(WORKFLOW_PATH).isFile shouldBe true
        workflow() shouldContain "'v*.*.*'"
    }

    test("세 OS 러너가 각자 자기 포맷을 packageDistributionForCurrentOS 로 만든다") {
        val text = workflow()

        listOf("macos-latest", "windows-latest", "ubuntu-latest").forEach { runner ->
            withClue(runner) { text shouldContain runner }
        }
        listOf("os: macos", "os: windows", "os: linux").forEach { token ->
            withClue(token) { text shouldContain token }
        }
        text shouldContain "packageDistributionForCurrentOS"
    }

    // needs 가 게이트다. 빠지면 한 OS 만 성공해도 릴리즈가 생겨 반쪽 자산이 남는다.
    test("발행 잡이 세 패키징 잡을 needs 로 기다린다") {
        val publishSection = workflow().substringAfter("  publish:")

        publishSection shouldContain "needs: package"
        publishSection shouldContain "publish-release.sh"
    }

    test("계약 판단은 packaging 스크립트가 하고 YAML 은 부르기만 한다") {
        val text = workflow()

        text shouldContain "release-assets.sh check-tag"
        text shouldContain "release-assets.sh stage"
        // 버전 숫자를 워크플로에 쓰지 않는다 — gradle.properties 가 SSOT 다.
        Regex("""\b\d+\.\d+\.\d+\b""").find(text)?.value shouldBe null
    }

    test("워크플로도 스크립트도 조회 좌표를 하드코딩하지 않는다") {
        workflow() shouldContain "github.ref_name"
        workflow().contains(REPOSITORY_COORDINATE) shouldBe false

        PACKAGING_SCRIPTS.forEach { name ->
            val script = File("../packaging/$name")
            withClue(name) {
                script.isFile shouldBe true
                script.readText().contains(REPOSITORY_COORDINATE) shouldBe false
            }
        }
    }

    // 서명·공증은 UND-25 가 범위 밖으로 선언했다. 이 워크플로가 되살리지 않는다.
    test("코드 서명·공증 단계를 넣지 않는다") {
        val text = workflow().lowercase()

        listOf("codesign", "notarize", "notarytool", "signtool").forEach { keyword ->
            withClue(keyword) { text.contains(keyword) shouldBe false }
        }
    }

    // UND-48 구현자는 이 문서만 읽고 클라이언트를 만든다. 하나라도 빠지면 릴리즈 페이지를 보고 추측한다.
    test("발행 계약 문서가 UND-48 이 필요한 값을 모두 담는다") {
        val text = contract()

        listOf(
            "undine-<version>-macos.dmg",
            "undine-<version>-windows.msi",
            "undine-<version>-linux.deb",
            "checksums.txt",
            "SHA-256",
            "vX.Y.Z",
            REPOSITORY_COORDINATE,
            "BuildInfo",
        ).forEach { required -> withClue(required) { text shouldContain required } }
    }
})
