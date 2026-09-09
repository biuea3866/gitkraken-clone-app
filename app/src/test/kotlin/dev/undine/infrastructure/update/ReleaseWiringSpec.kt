package dev.undine.infrastructure.update

import dev.undine.BuildInfo
import dev.undine.domain.update.AppVersion
import dev.undine.domain.update.ReleaseCoordinates
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

private const val CONTRACT_PATH = "packaging/RELEASE-CONTRACT.md"
private const val BUILD_SCRIPT_PATH = "app/build.gradle.kts"

/**
 * 레포 루트 기준 파일을 읽는다.
 *
 * 테스트 작업 디렉터리(`app/`)에서 위로 올라가며 찾는다 — `ReleaseScripts.locate` 와 같은 방식이다.
 * 상대 경로를 못 박으면 실행 위치가 달라질 때 조용히 빈 문자열을 읽는다.
 */
private fun repositoryFile(path: String): File =
    generateSequence(File("").absoluteFile) { directory -> directory.parentFile }
        .map { directory -> File(directory, path) }
        .firstOrNull { candidate -> candidate.isFile }
        ?: error("레포 루트에서 $path 를 찾지 못했습니다")

/**
 * 빌드가 심은 값이 **발행 계약과 같은 것을 가리키는지** 본다.
 *
 * 좌표가 어긋나면 업데이트는 예외를 던지지 않고 **조용히 아무것도 찾지 못한다** — 이 티켓의 주된
 * 실패 모양이라 배선 자체를 대조한다 (결정 D1·D2).
 *
 * **실제 릴리즈 응답은 여기서도 검증하지 못한다** (결정 D10) — 저장소에 릴리즈가 0건이라
 * 클라이언트가 진짜 응답을 읽어 본 적이 없다. 이 스펙이 대조하는 것은 문서와 배선까지다.
 */
class ReleaseWiringSpec : FunSpec({

    test("BuildInfo 의 릴리즈 좌표가 발행 계약 문서의 저장소와 같다") {
        val contract = repositoryFile(CONTRACT_PATH).readText()

        contract shouldContain BuildInfo.RELEASE_REPOSITORY
    }

    test("릴리즈 좌표는 owner/repository 형식이다") {
        ReleaseCoordinates.parse(BuildInfo.RELEASE_REPOSITORY).shouldNotBeNull()
    }

    test("실행 중인 버전은 태그가 담을 수 있는 형식이다 — 아니면 확인이 계약 실패로만 끝난다") {
        AppVersion.parse(BuildInfo.VERSION).shouldNotBeNull()
    }

    test("패키징 모듈 목록에 java.net.http 가 들어 있다") {
        // 빠지면 **빌드가 아니라 패키징된 앱의 실행 시점에** 실패한다 — 개발 실행으로는 드러나지 않는다.
        val buildScript = repositoryFile(BUILD_SCRIPT_PATH).readText()

        withClue("nativeDistributions.modules 에 java.net.http 가 필요합니다 (결정 D19)") {
            buildScript.contains("\"java.net.http\"") shouldBe true
        }
    }
})
