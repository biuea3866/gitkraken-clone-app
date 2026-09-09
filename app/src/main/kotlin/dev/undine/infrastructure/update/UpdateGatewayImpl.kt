package dev.undine.infrastructure.update

import dev.undine.domain.update.AppVersion
import dev.undine.domain.update.AvailableRelease
import dev.undine.domain.update.InstallerLaunchResult
import dev.undine.domain.update.ReleaseCoordinates
import dev.undine.domain.update.UpdateCheckResult
import dev.undine.domain.update.UpdateDownloadResult
import dev.undine.domain.update.UpdateFailureKind
import dev.undine.domain.update.UpdateGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 앱 데이터 디렉터리 아래의 업데이트 전용 하위 디렉터리. 우리가 만드는 파일은 여기에만 둔다 (결정 D15). */
internal const val UPDATE_DIRECTORY: String = "update"

/**
 * 받는 중인 파일이 쓰는 이름의 접미사.
 *
 * 최종 이름과 갈라 두는 이유는 **먼저 지우고 나중에 받지 않기** 위해서다 (결정 D22) — 재시도가
 * 자리부터 비우면 그 뒤의 실패·취소가 이미 검증을 통과한 파일까지 가져간다.
 */
internal const val STAGING_SUFFIX: String = ".part"

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val CHECKSUM_ALGORITHM = "SHA-256"
private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024
private const val HEX_MASK = 0xFF

/**
 * [UpdateGateway] 의 구현.
 *
 * 계약 해석은 [parseLatestRelease]·[parseChecksums] 한 곳에 있고 (`packaging/RELEASE-CONTRACT.md`),
 * HTTP·파일 열기는 주입 가능한 경계 뒤에 있어 테스트가 네트워크와 데스크톱을 타지 않는다.
 *
 * **인증 토큰을 쓰지 않는다** (결정 D21) — 공개 저장소이고 하루 한 번이면 비인증 한도로 충분하다.
 *
 * @param releaseRepository `owner/repository`. `BuildInfo` 가 빌드 시점에 심은 값이다.
 * @param currentVersion 지금 실행 중인 앱 버전. `BuildInfo.VERSION` 이다.
 * @param appDirectory 설정·로그가 놓이는 앱 디렉터리. 경로 정책은 창 소유자 한 곳이 정한다 (결정 G35).
 */
class UpdateGatewayImpl(
    private val releaseRepository: String,
    private val currentVersion: String,
    private val appDirectory: Path,
    private val httpClient: ReleaseHttpClient = JdkReleaseHttpClient(),
    private val installerOpener: InstallerOpener = DesktopInstallerOpener(),
    osName: String = System.getProperty("os.name").orEmpty(),
) : UpdateGateway {

    private val releaseOs = ReleaseOs.of(osName)

    override suspend fun checkForUpdate(): UpdateCheckResult {
        val coordinates = ReleaseCoordinates.parse(releaseRepository)
        val installed = AppVersion.parse(currentVersion)
        return if (coordinates == null || installed == null) {
            // 빌드가 심은 값이 계약 형식이 아니다 — 응답 탓이 아니라 배선이 틀린 것이라 실패로 올린다.
            failedCheck(
                kind = UpdateFailureKind.CONTRACT,
                detail = "릴리즈 좌표·버전이 계약 형식이 아닙니다: $releaseRepository@$currentVersion",
            )
        } else {
            requestLatestRelease(coordinates, installed)
        }
    }

    override suspend fun downloadAndVerify(release: AvailableRelease): UpdateDownloadResult {
        val staging = try {
            prepareStaging(release.assetName)
        } catch (failure: IOException) {
            return downloadFailed(UpdateFailureKind.NETWORK, "업데이트 디렉터리를 만들지 못했습니다: ${failure.message}")
        }
        return try {
            fetchAndVerify(release, staging)
        } catch (failure: IOException) {
            deleteQuietly(staging)
            downloadFailed(UpdateFailureKind.NETWORK, "업데이트 파일을 받지 못했습니다: ${failure.message}")
        }
    }

    override suspend fun openInstaller(installer: Path): InstallerLaunchResult = try {
        installerOpener.open(installer)
        InstallerLaunchResult.Opened(installer)
    } catch (failure: IOException) {
        // **파일을 지우지 않는다** (결정 D18) — 검증을 통과한 정상 파일이고 실패한 것은 여는 동작이다.
        logQuietly("update.open-installer-failed type=${failure::class.simpleName}")
        InstallerLaunchResult.OpenFailed(installer, failure.message ?: "설치 파일을 열지 못했습니다")
    }

    private suspend fun requestLatestRelease(
        coordinates: ReleaseCoordinates,
        installed: AppVersion,
    ): UpdateCheckResult {
        val response = try {
            httpClient.getText(latestReleaseUri(coordinates))
        } catch (failure: IOException) {
            return failedCheck(UpdateFailureKind.NETWORK, "릴리즈를 조회하지 못했습니다: ${failure.message}")
        }
        return interpret(response, installed)
    }

    private fun interpret(response: ReleaseHttpResponse, installed: AppVersion): UpdateCheckResult =
        when (response.statusCode) {
            HTTP_OK -> compare(response.body, installed)
            // 릴리즈가 0건인 저장소가 주는 답이다. "최신입니다" 와 같은 값으로 만들지 않는다 (결정 D21).
            HTTP_NOT_FOUND -> UpdateCheckResult.NoRelease
            else -> failedCheck(UpdateFailureKind.NETWORK, "릴리즈 조회 응답 코드 ${response.statusCode}")
        }

    private fun compare(body: String, installed: AppVersion): UpdateCheckResult {
        val release = parseLatestRelease(body, releaseOs)
            ?: return failedCheck(UpdateFailureKind.CONTRACT, "릴리즈 응답이 발행 계약과 다릅니다")
        return if (release.version > installed) {
            UpdateCheckResult.UpdateAvailable(release)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /**
     * 자산과 `checksums.txt` 를 [staging] 에 받아 대조하고, **통과한 뒤에만** 최종 자리로 옮긴다.
     *
     * 어느 실패 경로든 지우는 것은 **[staging] 하나뿐**이다 (결정 D5·D22) — 같은 이름의 기존 검증
     * 완료 파일은 새 파일이 검증을 통과하기 전까지 자리를 내주지 않는다.
     */
    private suspend fun fetchAndVerify(release: AvailableRelease, staging: Path): UpdateDownloadResult {
        val addresses = releaseAddressesOf(release)
            ?: return discardAndFail(staging, UpdateFailureKind.CONTRACT, "릴리즈가 준 주소가 http(s) 절대 주소가 아닙니다")
        val assetStatus = httpClient.downloadTo(addresses.asset, staging)
        val expected = if (assetStatus == HTTP_OK) {
            expectedChecksum(httpClient.getText(addresses.checksums), release.assetName)
        } else {
            null
        }
        return when {
            assetStatus != HTTP_OK ->
                discardAndFail(staging, UpdateFailureKind.NETWORK, "자산 응답 코드 $assetStatus")
            expected == null ->
                discardAndFail(staging, UpdateFailureKind.CONTRACT, "checksums.txt 가 발행 계약과 다릅니다")
            else -> verify(release, staging, expected)
        }
    }

    private suspend fun verify(
        release: AvailableRelease,
        staging: Path,
        expected: String,
    ): UpdateDownloadResult {
        val actual = withContext(Dispatchers.IO) { sha256Of(staging) }
        if (actual != expected) {
            deleteQuietly(staging)
            logQuietly("update.checksum-mismatch asset=${release.assetName}")
            return UpdateDownloadResult.ChecksumMismatch(release.assetName)
        }
        return promote(staging, release.assetName)
    }

    /**
     * 검증을 통과한 [staging] 을 최종 이름으로 옮긴다.
     *
     * 옛 파일 삭제는 이 교체가 하는 일뿐이다 — **새 파일이 검증을 통과한 뒤**에만 일어나므로
     * 그 앞의 어떤 실패도 옛 파일을 가져가지 못한다 (결정 D22).
     */
    private suspend fun promote(staging: Path, assetName: String): UpdateDownloadResult =
        withContext(Dispatchers.IO) {
            val installer = staging.resolveSibling(assetName)
            try {
                moveOnto(staging, installer)
                UpdateDownloadResult.Verified(installer)
            } catch (failure: IOException) {
                deleteQuietly(staging)
                downloadFailed(UpdateFailureKind.NETWORK, "받은 파일을 자리에 놓지 못했습니다: ${failure.message}")
            }
        }

    /** 받을 자리를 [STAGING_SUFFIX] 로 갈라 둔다 — 지우는 것은 이전에 받다 만 임시 파일뿐이다. */
    private suspend fun prepareStaging(assetName: String): Path = withContext(Dispatchers.IO) {
        val directory = appDirectory.resolve(UPDATE_DIRECTORY)
        Files.createDirectories(directory)
        directory.resolve(assetName + STAGING_SUFFIX).also(Files::deleteIfExists)
    }
}

/** 원자적 교체를 먼저 시도하고, 파일 시스템이 지원하지 않으면 덮어쓰기로 내려온다. */
private fun moveOnto(staging: Path, installer: Path) {
    try {
        Files.move(staging, installer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        logQuietly("update.atomic-move-unsupported detail=${unsupported.message}")
        Files.move(staging, installer, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun expectedChecksum(response: ReleaseHttpResponse, assetName: String): String? =
    if (response.statusCode == HTTP_OK) parseChecksums(response.body)?.get(assetName) else null

/**
 * 받다 만 파일을 치운다. **지우지 못해도 흐름을 막지 않는다** — `.part` 는 고정 경로라 다음
 * 다운로드가 같은 자리를 덮는다.
 *
 * 그래서 **화면 문구가 삭제를 단정하지 않는다.** 지웠다고 말해 놓고 실패하면 화면이 거짓말한다 —
 * 사용자에게 필요한 사실은 "설치하지 않았다" 와 "기존 설치본은 그대로다" 둘이고, 그 둘은 삭제
 * 성공 여부와 무관하게 참이다.
 */
private suspend fun deleteQuietly(target: Path) {
    withContext(Dispatchers.IO) {
        try {
            Files.deleteIfExists(target)
        } catch (failure: IOException) {
            logQuietly("update.delete-failed type=${failure::class.simpleName}")
        }
    }
}

private fun failedCheck(kind: UpdateFailureKind, detail: String): UpdateCheckResult {
    logQuietly("update.check-failed kind=$kind detail=$detail")
    return UpdateCheckResult.CheckFailed(kind, detail)
}

/**
 * 받다 만 파일을 치우고 실패로 접는다.
 *
 * 이 흐름의 어떤 실패든 치우는 것은 **[staging] 하나뿐**이다 (결정 D5·D22) — 실패 종류마다
 * 지우는 대상이 갈리면 언젠가 하나가 사용자 파일을 가리킨다.
 */
private suspend fun discardAndFail(
    staging: Path,
    kind: UpdateFailureKind,
    detail: String,
): UpdateDownloadResult {
    deleteQuietly(staging)
    return downloadFailed(kind, detail)
}

private fun downloadFailed(kind: UpdateFailureKind, detail: String): UpdateDownloadResult {
    logQuietly("update.download-failed kind=$kind detail=$detail")
    return UpdateDownloadResult.DownloadFailed(kind, detail)
}

/** `GET /repos/{owner}/{repo}/releases/latest` (`packaging/RELEASE-CONTRACT.md`). */
internal fun latestReleaseUri(coordinates: ReleaseCoordinates): URI =
    URI.create("https://api.github.com/repos/${coordinates.owner}/${coordinates.repository}/releases/latest")

/**
 * 화면에 띄우지 않고 로그에만 남긴다 (결정 D6).
 *
 * 매번 오류를 띄우면 오프라인 사용자에게 소음이지만, **실패를 성공으로 접지도 않는다** — 상태는
 * 결과 타입이 구분해 올리고 여기서는 사유만 기록한다.
 */
private fun logQuietly(message: String) {
    System.err.println("[undine] $message")
}

private fun sha256Of(file: Path): String {
    val digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM)
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read > 0) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and HEX_MASK) }
}
