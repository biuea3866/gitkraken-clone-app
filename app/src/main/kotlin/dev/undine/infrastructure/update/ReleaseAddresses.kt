package dev.undine.infrastructure.update

import dev.undine.domain.update.AvailableRelease
import java.net.URI

/** 릴리즈가 준 주소로 받아들이는 스킴. 그 밖은 받으러 가지 않는다. */
private val HTTP_SCHEMES = setOf("http", "https")

/**
 * 받으러 갈 두 주소.
 *
 * **하나라도 계약과 다르면 이 값이 만들어지지 않는다** — 반쪽만 유효한 주소 쌍이 존재할 수 없어야
 * 호출부가 "자산은 받았는데 체크섬 주소가 이상하다" 같은 중간 상태를 다루지 않는다.
 */
internal data class ReleaseAddresses(val asset: URI, val checksums: URI)

/** 두 주소가 모두 http(s) 절대 주소일 때만 만든다. 아니면 `null` 이다. */
internal fun releaseAddressesOf(release: AvailableRelease): ReleaseAddresses? {
    val asset = httpUriOrNull(release.assetUrl)
    val checksums = httpUriOrNull(release.checksumsUrl)
    return if (asset != null && checksums != null) ReleaseAddresses(asset, checksums) else null
}

/**
 * 계약이 준 주소를 http(s) **절대** 주소일 때만 돌려준다. 아니면 `null` 이다.
 *
 * `URI.create` 는 형식이 어긋나면 `IllegalArgumentException` 을 던지는데, 그것은 다운로드 흐름이
 * 잡는 `IOException` 경계를 **비껴가 코루틴을 죽인다.** 계약과 다른 응답은 앱을 죽일 일이 아니라
 * 보고할 실패다 (결정 D21) — 값을 먼저 확인하고 실패로 접는다.
 */
private fun httpUriOrNull(value: String): URI? =
    runCatching { URI(value) }.getOrNull()
        ?.takeIf { it.isAbsolute && it.scheme?.lowercase() in HTTP_SCHEMES && it.host != null }
