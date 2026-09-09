package dev.undine.infrastructure.update

import dev.undine.domain.update.AppVersion
import dev.undine.domain.update.AvailableRelease
import dev.undine.infrastructure.settings.JsonFormatException
import dev.undine.infrastructure.settings.JsonParser

/**
 * `packaging/RELEASE-CONTRACT.md` 를 코드로 옮긴 자리. **이 파일이 계약 해석의 유일한 지점이다.**
 *
 * 계약과 어긋난 응답은 관대하게 넘기지 않고 `null` 로 보고한다 (결정 D1·D10) — 빠진 필드를
 * 기본값으로 메우거나 비슷한 이름의 자산을 골라 주면, 잘못된 파일이 체크섬 대조까지 통과한다.
 */

/** 릴리즈마다 하나 붙는 체크섬 목록 자산. 자기 자신은 목록에 들어가지 않는다. */
internal const val CHECKSUMS_ASSET_NAME: String = "checksums.txt"

private const val FIELD_TAG_NAME = "tag_name"
private const val FIELD_BODY = "body"
private const val FIELD_ASSETS = "assets"
private const val FIELD_ASSET_NAME = "name"
private const val FIELD_ASSET_URL = "browser_download_url"

/** `<64자리 소문자 hex><공백 2개><파일명>`. `shasum -a 256` 텍스트 모드 출력 그대로다. */
private val CHECKSUM_LINE = Regex("""([0-9a-f]{64}) {2}(\S+)""")

/**
 * 계약이 정한 OS 토큰과 확장자. 러너 이름(`ubuntu-latest`)이 아니라 이 셋만 쓴다.
 *
 * 알 수 없는 OS 는 [LINUX] 로 본다 — `SettingsPaths` 가 설정 경로를 정할 때 쓰는 것과 같은 분기다.
 * 자산이 실제로 없으면 계약 위반으로 드러나므로, 여기서 추측이 조용히 통과하지 않는다.
 */
internal enum class ReleaseOs(val token: String, val extension: String) {
    MACOS("macos", "dmg"),
    WINDOWS("windows", "msi"),
    LINUX("linux", "deb"),
    ;

    companion object {

        fun of(osName: String): ReleaseOs = when {
            osName.startsWith("mac", ignoreCase = true) -> MACOS
            osName.startsWith("windows", ignoreCase = true) -> WINDOWS
            else -> LINUX
        }
    }
}

/** `undine-<version>-<os>.<ext>`. Gradle 산출물 이름이 아니라 **발행이 옮겨 놓은 계약명**이다. */
internal fun assetNameFor(version: AppVersion, os: ReleaseOs): String =
    "undine-$version-${os.token}.${os.extension}"

/**
 * `checksums.txt` 를 `파일명 → 체크섬` 으로 읽는다. 한 줄이라도 형식에서 벗어나면 `null` 이다.
 *
 * 어긋난 줄을 건너뛰지 않는 이유: 건너뛰면 우리가 찾는 자산의 줄이 깨졌을 때 "목록에 없음" 과
 * 구분되지 않고, 둘 다 조용히 검증을 건너뛰는 쪽으로 흐른다.
 */
internal fun parseChecksums(text: String): Map<String, String>? {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val entries = lines.map(CHECKSUM_LINE::matchEntire)
    return if (lines.isEmpty() || entries.any { it == null }) {
        null
    } else {
        entries.filterNotNull().associate { match -> match.groupValues[2] to match.groupValues[1] }
    }
}

/**
 * `GET /repos/{owner}/{repo}/releases/latest` 응답을 이 OS 의 [AvailableRelease] 로 읽는다.
 *
 * 계약이 다루는 것(태그 형식 · 자산 이름 · `checksums.txt` 존재)은 하나라도 어긋나면 `null` 이다.
 * 릴리즈 노트는 계약이 다루지 않으므로 **없을 수 있다** — 없으면 빈 문자열이고, 문자열이 아닌 값이
 * 오면 응답 자체가 이상한 것이라 계약 위반으로 본다.
 */
internal fun parseLatestRelease(body: String, os: ReleaseOs): AvailableRelease? {
    val fields = parseObject(body) ?: return null
    return buildRelease(
        version = (fields[FIELD_TAG_NAME] as? String)?.let(AppVersion::parseTag),
        releaseNotes = readReleaseNotes(fields),
        downloadUrls = readDownloadUrls(fields[FIELD_ASSETS]),
        os = os,
    )
}

private fun buildRelease(
    version: AppVersion?,
    releaseNotes: String?,
    downloadUrls: Map<String, String>?,
    os: ReleaseOs,
): AvailableRelease? {
    if (version == null || releaseNotes == null || downloadUrls == null) return null
    val assetName = assetNameFor(version, os)
    val assetUrl = downloadUrls[assetName]
    val checksumsUrl = downloadUrls[CHECKSUMS_ASSET_NAME]
    return if (assetUrl == null || checksumsUrl == null) {
        null
    } else {
        AvailableRelease(
            version = version,
            releaseNotes = releaseNotes,
            assetName = assetName,
            assetUrl = assetUrl,
            checksumsUrl = checksumsUrl,
        )
    }
}

/**
 * 응답 최상위 객체. 파싱 자체가 실패하면 `null` 이다.
 *
 * **update 전용 JSON 파서를 만들지 않는다** (결정 D20) — 설정이 쓰는 [JsonParser] 를 그대로 읽어
 * 쓴다. 그 파일은 수정하지 않는다.
 */
private fun parseObject(body: String): Map<*, *>? {
    val document = try {
        JsonParser(body).parseDocument()
    } catch (malformed: JsonFormatException) {
        // 계약 위반과 같은 취급이다 — 응답이 JSON 조차 아니면 추측할 것이 없다. 사유는 삼키지 않고
        // 남긴다: 조용히 "업데이트 없음" 으로 보이는 것이 이 기능의 실패 모양이다 (결정 D1).
        System.err.println("[undine] update.release-json-malformed reason=${malformed.message}")
        return null
    }
    return document as? Map<*, *>
}

/** 없거나 `null` 이면 빈 노트, 문자열이 아니면 계약 위반(`null`)이다. */
private fun readReleaseNotes(fields: Map<*, *>): String? = when (val body = fields[FIELD_BODY]) {
    null -> ""
    is String -> body
    else -> null
}

/** 자산 이름 → 다운로드 URL. 목록이 아니면 `null` 이고, 이름·URL 이 빠진 항목은 목록에서 빠진다. */
private fun readDownloadUrls(value: Any?): Map<String, String>? =
    (value as? List<*>)
        ?.filterIsInstance<Map<*, *>>()
        ?.mapNotNull { asset ->
            val name = asset[FIELD_ASSET_NAME] as? String
            val url = asset[FIELD_ASSET_URL] as? String
            if (name == null || url == null) null else name to url
        }
        ?.toMap()
