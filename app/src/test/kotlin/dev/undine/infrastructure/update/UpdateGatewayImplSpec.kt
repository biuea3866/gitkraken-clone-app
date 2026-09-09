package dev.undine.infrastructure.update

import dev.undine.domain.update.AppVersion
import dev.undine.domain.update.InstallerLaunchResult
import dev.undine.domain.update.ReleaseCoordinates
import dev.undine.domain.update.UpdateCheckResult
import dev.undine.domain.update.UpdateDownloadResult
import dev.undine.domain.update.UpdateFailureKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val REPOSITORY = "biuea3866/gitkraken-clone-app"
private const val INSTALLED_VERSION = "1.0.0"
private const val LINUX_OS = "Linux"

private const val ASSET_NAME = "undine-2.0.0-linux.deb"
private const val ASSET_URL = "https://x.invalid/deb"
private const val CHECKSUMS_URL = "https://x.invalid/checksums"
private const val LATEST_URL =
    "https://api.github.com/repos/biuea3866/gitkraken-clone-app/releases/latest"

private const val ASSET_BODY = "설치 파일 내용"

private val RELEASE_JSON = """
    {
      "tag_name": "v2.0.0",
      "body": "그래프 열 폭 고정",
      "assets": [
        {"name": "$ASSET_NAME", "browser_download_url": "$ASSET_URL"},
        {"name": "checksums.txt", "browser_download_url": "$CHECKSUMS_URL"}
      ]
    }
""".trimIndent()

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

/**
 * 미리 정한 응답만 돌려주는 HTTP 경계. **실제 github.com 을 부르지 않는다** (결정 D3).
 *
 * 다운로드는 [assetBody] 를 그대로 파일에 쓴다 — 체크섬 대조가 실제 파일 내용을 보는지 확인하려면
 * 바이트가 실제로 디스크에 있어야 한다.
 */
private class FakeReleaseHttpClient(
    private val texts: Map<String, ReleaseHttpResponse> = emptyMap(),
    private val assetStatus: Int = 200,
    private val assetBody: String = ASSET_BODY,
    private val failure: IOException? = null,
) : ReleaseHttpClient {

    /** 실제로 나간 요청 주소. "가지 않았다" 를 단언할 수 있어야 한다. */
    val requested = mutableListOf<String>()

    override suspend fun getText(uri: URI): ReleaseHttpResponse {
        requested += uri.toString()
        failure?.let { throw it }
        return texts[uri.toString()] ?: ReleaseHttpResponse(statusCode = 500, body = "")
    }

    override suspend fun downloadTo(uri: URI, target: Path): Int {
        requested += uri.toString()
        failure?.let { throw it }
        Files.writeString(target, assetBody)
        return assetStatus
    }
}

private class FakeInstallerOpener(private val failure: IOException? = null) : InstallerOpener {

    val opened = mutableListOf<Path>()

    override suspend fun open(installer: Path) {
        failure?.let { throw it }
        opened.add(installer)
    }
}

private fun okTexts(checksums: String): Map<String, ReleaseHttpResponse> = mapOf(
    LATEST_URL to ReleaseHttpResponse(200, RELEASE_JSON),
    CHECKSUMS_URL to ReleaseHttpResponse(200, checksums),
)

private fun gatewayIn(
    appDirectory: Path,
    httpClient: ReleaseHttpClient,
    opener: InstallerOpener = FakeInstallerOpener(),
    releaseRepository: String = REPOSITORY,
    currentVersion: String = INSTALLED_VERSION,
) = UpdateGatewayImpl(
    releaseRepository = releaseRepository,
    currentVersion = currentVersion,
    appDirectory = appDirectory,
    httpClient = httpClient,
    installerOpener = opener,
    osName = LINUX_OS,
)

/**
 * 확인 · 다운로드 · 검증 · 열기의 실제 경로. HTTP 와 데스크톱만 대역이고 **파일은 진짜로 쓴다** —
 * 삭제 대상이 우리가 받은 파일 하나로 좁혀졌는지는 실제 디렉터리에서만 확인된다 (결정 D5).
 *
 * 실행 중인 앱 설치본은 어디에서도 건드리지 않는다 (결정 D12) — 교체·재시작·세대 보관 경로가
 * 애초에 없어서, 그 동작을 검증할 것도 없다.
 */
class UpdateGatewayImplSpec : FunSpec({

    test("조회 좌표는 계약이 정한 최신 릴리즈 엔드포인트다") {
        latestReleaseUri(ReleaseCoordinates("biuea3866", "gitkraken-clone-app")).toString() shouldBe LATEST_URL
    }

    test("기본 HTTP 클라이언트는 리다이렉트를 따라간다 — 자산 다운로드가 302 본문을 받지 않게") {
        defaultReleaseHttpClient().followRedirects() shouldBe HttpClient.Redirect.NORMAL
    }

    test("더 높은 버전의 릴리즈는 새 버전으로 올라온다") {
        val gateway = gatewayIn(tempdir().toPath(), FakeReleaseHttpClient(okTexts("")))

        val result = runBlocking { gateway.checkForUpdate() }

        val available = result.shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>()
        available.release.version shouldBe AppVersion(2, 0, 0)
        available.release.assetName shouldBe ASSET_NAME
        available.release.releaseNotes shouldBe "그래프 열 폭 고정"
    }

    test("같은 버전이면 업데이트 없음이다") {
        val gateway = gatewayIn(
            tempdir().toPath(),
            FakeReleaseHttpClient(okTexts("")),
            currentVersion = "2.0.0",
        )

        runBlocking { gateway.checkForUpdate() } shouldBe UpdateCheckResult.UpToDate
    }

    test("404 는 릴리즈 없음이며 업데이트 없음과 다른 값이다") {
        val http = FakeReleaseHttpClient(mapOf(LATEST_URL to ReleaseHttpResponse(404, "Not Found")))

        runBlocking { gatewayIn(tempdir().toPath(), http).checkForUpdate() } shouldBe UpdateCheckResult.NoRelease
    }

    test("네트워크 실패는 업데이트 없음으로 접히지 않는다") {
        val http = FakeReleaseHttpClient(failure = IOException("연결할 수 없습니다"))

        val result = runBlocking { gatewayIn(tempdir().toPath(), http).checkForUpdate() }

        result.shouldBeInstanceOf<UpdateCheckResult.CheckFailed>().kind shouldBe UpdateFailureKind.NETWORK
    }

    test("계약과 다른 응답은 계약 실패다") {
        val http = FakeReleaseHttpClient(
            mapOf(LATEST_URL to ReleaseHttpResponse(200, """{"tag_name": "release-2", "assets": []}""")),
        )

        val result = runBlocking { gatewayIn(tempdir().toPath(), http).checkForUpdate() }

        result.shouldBeInstanceOf<UpdateCheckResult.CheckFailed>().kind shouldBe UpdateFailureKind.CONTRACT
    }

    test("빌드가 심은 좌표가 형식 밖이면 확인 자체가 계약 실패다") {
        val http = FakeReleaseHttpClient(okTexts(""))

        val result = runBlocking {
            gatewayIn(tempdir().toPath(), http, releaseRepository = "gitkraken-clone-app").checkForUpdate()
        }

        result.shouldBeInstanceOf<UpdateCheckResult.CheckFailed>().kind shouldBe UpdateFailureKind.CONTRACT
    }

    test("체크섬이 맞으면 앱 데이터 디렉터리 아래 update 폴더에만 파일이 남는다") {
        val appDirectory = tempdir().toPath()
        val checksums = "${sha256Hex(ASSET_BODY)}  $ASSET_NAME\n"
        val gateway = gatewayIn(appDirectory, FakeReleaseHttpClient(okTexts(checksums)))
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release

        val downloaded = runBlocking { gateway.downloadAndVerify(release) }

        val verified = downloaded.shouldBeInstanceOf<UpdateDownloadResult.Verified>()
        verified.installer shouldBe appDirectory.resolve(UPDATE_DIRECTORY).resolve(ASSET_NAME)
        Files.readString(verified.installer) shouldBe ASSET_BODY
    }

    test("체크섬이 맞지 않으면 받은 파일 하나만 지우고 기존 파일은 건드리지 않는다") {
        val appDirectory = tempdir().toPath()
        val keeper = Files.createDirectories(appDirectory.resolve(UPDATE_DIRECTORY)).resolve("이전-기록.txt")
        Files.writeString(keeper, "남아 있어야 한다")
        val checksums = "${sha256Hex("다른 내용")}  $ASSET_NAME\n"
        val gateway = gatewayIn(appDirectory, FakeReleaseHttpClient(okTexts(checksums)))
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release

        val downloaded = runBlocking { gateway.downloadAndVerify(release) }

        downloaded shouldBe UpdateDownloadResult.ChecksumMismatch(ASSET_NAME)
        Files.exists(appDirectory.resolve(UPDATE_DIRECTORY).resolve(ASSET_NAME)) shouldBe false
        Files.readString(keeper) shouldBe "남아 있어야 한다"
    }

    test("checksums.txt 가 계약과 다르면 받은 파일을 지우고 계약 실패로 보고한다") {
        val appDirectory = tempdir().toPath()
        val gateway = gatewayIn(appDirectory, FakeReleaseHttpClient(okTexts("체크섬 아님")))
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release

        val downloaded = runBlocking { gateway.downloadAndVerify(release) }

        downloaded.shouldBeInstanceOf<UpdateDownloadResult.DownloadFailed>().kind shouldBe UpdateFailureKind.CONTRACT
        Files.exists(appDirectory.resolve(UPDATE_DIRECTORY).resolve(ASSET_NAME)) shouldBe false
    }

    test("릴리즈가 준 주소가 http 절대 주소가 아니면 코루틴을 죽이지 않고 계약 실패로 보고한다") {
        val appDirectory = tempdir().toPath()
        val gateway = gatewayIn(appDirectory, FakeReleaseHttpClient(okTexts("쓰이지 않는다")))
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release

        // URI.create 는 여기서 IllegalArgumentException 을 던진다 — 그것은 IOException 경계를 비껴간다.
        val downloaded = runBlocking {
            gateway.downloadAndVerify(release.copy(assetUrl = "이건 주소가 아니다"))
        }

        downloaded.shouldBeInstanceOf<UpdateDownloadResult.DownloadFailed>().kind shouldBe UpdateFailureKind.CONTRACT
        Files.exists(appDirectory.resolve(UPDATE_DIRECTORY).resolve(ASSET_NAME)) shouldBe false
    }

    test("http 가 아닌 스킴은 받으러 가지 않고 계약 실패로 보고한다") {
        val appDirectory = tempdir().toPath()
        val http = FakeReleaseHttpClient(okTexts("쓰이지 않는다"))
        val gateway = gatewayIn(appDirectory, http)
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release
        http.requested.clear()

        val downloaded = runBlocking {
            gateway.downloadAndVerify(release.copy(assetUrl = "file:///etc/passwd"))
        }

        downloaded.shouldBeInstanceOf<UpdateDownloadResult.DownloadFailed>().kind shouldBe UpdateFailureKind.CONTRACT
        // 결과 등급만 보면 체크섬 파싱 실패로도 같은 값이 나온다 — 아예 가지 않았음을 못박는다.
        http.requested.shouldBeEmpty()
    }

    test("자산 응답이 200 이 아니면 받다 만 파일을 남기지 않는다") {
        val appDirectory = tempdir().toPath()
        val checksums = "${sha256Hex(ASSET_BODY)}  $ASSET_NAME\n"
        val http = FakeReleaseHttpClient(okTexts(checksums), assetStatus = 500, assetBody = "오류 페이지")
        val gateway = gatewayIn(appDirectory, http)
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release

        val downloaded = runBlocking { gateway.downloadAndVerify(release) }

        downloaded.shouldBeInstanceOf<UpdateDownloadResult.DownloadFailed>().kind shouldBe UpdateFailureKind.NETWORK
        Files.exists(appDirectory.resolve(UPDATE_DIRECTORY).resolve(ASSET_NAME)) shouldBe false
    }

    test("재시도가 네트워크로 실패해도 같은 이름의 기존 검증 파일은 그대로 남는다") {
        val appDirectory = tempdir().toPath()
        val installer = Files.createDirectories(appDirectory.resolve(UPDATE_DIRECTORY)).resolve(ASSET_NAME)
        Files.writeString(installer, "먼저 받아 검증까지 끝낸 파일")
        val checksums = "${sha256Hex(ASSET_BODY)}  $ASSET_NAME\n"
        val http = FakeReleaseHttpClient(okTexts(checksums), assetStatus = 500, assetBody = "오류 페이지")
        val gateway = gatewayIn(appDirectory, http)
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release

        val downloaded = runBlocking { gateway.downloadAndVerify(release) }

        downloaded.shouldBeInstanceOf<UpdateDownloadResult.DownloadFailed>().kind shouldBe UpdateFailureKind.NETWORK
        Files.readString(installer) shouldBe "먼저 받아 검증까지 끝낸 파일"
        Files.exists(installer.resolveSibling(ASSET_NAME + STAGING_SUFFIX)) shouldBe false
    }

    test("재시도가 체크섬으로 실패해도 같은 이름의 기존 검증 파일은 그대로 남는다") {
        val appDirectory = tempdir().toPath()
        val installer = Files.createDirectories(appDirectory.resolve(UPDATE_DIRECTORY)).resolve(ASSET_NAME)
        Files.writeString(installer, "먼저 받아 검증까지 끝낸 파일")
        val checksums = "${sha256Hex("다른 내용")}  $ASSET_NAME\n"
        val gateway = gatewayIn(appDirectory, FakeReleaseHttpClient(okTexts(checksums)))
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release

        val downloaded = runBlocking { gateway.downloadAndVerify(release) }

        downloaded shouldBe UpdateDownloadResult.ChecksumMismatch(ASSET_NAME)
        Files.readString(installer) shouldBe "먼저 받아 검증까지 끝낸 파일"
        Files.exists(installer.resolveSibling(ASSET_NAME + STAGING_SUFFIX)) shouldBe false
    }

    test("HTTP 자체가 실패해도 기존 검증 파일은 그대로 남는다") {
        val appDirectory = tempdir().toPath()
        val installer = Files.createDirectories(appDirectory.resolve(UPDATE_DIRECTORY)).resolve(ASSET_NAME)
        Files.writeString(installer, "먼저 받아 검증까지 끝낸 파일")
        val release = runBlocking {
            gatewayIn(appDirectory, FakeReleaseHttpClient(okTexts(""))).checkForUpdate()
        }.shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release
        val offline = gatewayIn(appDirectory, FakeReleaseHttpClient(failure = IOException("연결할 수 없습니다")))

        val downloaded = runBlocking { offline.downloadAndVerify(release) }

        downloaded.shouldBeInstanceOf<UpdateDownloadResult.DownloadFailed>().kind shouldBe UpdateFailureKind.NETWORK
        Files.readString(installer) shouldBe "먼저 받아 검증까지 끝낸 파일"
        Files.exists(installer.resolveSibling(ASSET_NAME + STAGING_SUFFIX)) shouldBe false
    }

    test("검증을 통과하면 옛 파일 자리를 새 파일이 차지하고 임시 파일은 남지 않는다") {
        val appDirectory = tempdir().toPath()
        val installer = Files.createDirectories(appDirectory.resolve(UPDATE_DIRECTORY)).resolve(ASSET_NAME)
        Files.writeString(installer, "이전에 받은 파일")
        val checksums = "${sha256Hex(ASSET_BODY)}  $ASSET_NAME\n"
        val gateway = gatewayIn(appDirectory, FakeReleaseHttpClient(okTexts(checksums)))
        val release = runBlocking { gateway.checkForUpdate() }
            .shouldBeInstanceOf<UpdateCheckResult.UpdateAvailable>().release

        val downloaded = runBlocking { gateway.downloadAndVerify(release) }

        downloaded.shouldBeInstanceOf<UpdateDownloadResult.Verified>().installer shouldBe installer
        Files.readString(installer) shouldBe ASSET_BODY
        Files.exists(installer.resolveSibling(ASSET_NAME + STAGING_SUFFIX)) shouldBe false
    }

    test("열기에 성공해도 파일은 남는다 — 설치 도중에 지우면 설치가 깨진다") {
        val appDirectory = tempdir().toPath()
        val installer = Files.createDirectories(appDirectory.resolve(UPDATE_DIRECTORY)).resolve(ASSET_NAME)
        Files.writeString(installer, ASSET_BODY)
        val opener = FakeInstallerOpener()
        val gateway = gatewayIn(appDirectory, FakeReleaseHttpClient(), opener)

        val launched = runBlocking { gateway.openInstaller(installer) }

        launched shouldBe InstallerLaunchResult.Opened(installer)
        opener.opened shouldBe listOf(installer)
        Files.exists(installer) shouldBe true
    }

    test("열기에 실패해도 파일을 지우지 않고 위치와 사유를 함께 올린다") {
        val appDirectory = tempdir().toPath()
        val installer = Files.createDirectories(appDirectory.resolve(UPDATE_DIRECTORY)).resolve(ASSET_NAME)
        Files.writeString(installer, ASSET_BODY)
        val opener = FakeInstallerOpener(IOException("데스크톱 연동이 없습니다"))
        val gateway = gatewayIn(appDirectory, FakeReleaseHttpClient(), opener)

        val launched = runBlocking { gateway.openInstaller(installer) }

        launched shouldBe InstallerLaunchResult.OpenFailed(installer, "데스크톱 연동이 없습니다")
        Files.exists(installer) shouldBe true
    }
})
