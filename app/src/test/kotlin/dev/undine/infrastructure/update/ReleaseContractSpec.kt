package dev.undine.infrastructure.update

import dev.undine.domain.update.AppVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private const val MACOS_CHECKSUM = "3e62c6b05e1130129263e602136a6318db0d93e2fccf8cc4807d409d24d9c952"
private const val CHECKSUMS_CHECKSUM = "1d1dd9a0c14cb4f0b823b22a1a20e70889886edfbfeeb06b93a6b01bfb64cc6f"

/** 계약이 정한 형식의 응답. 필드를 하나씩 흔들어 무엇이 계약 위반인지 본다. */
private fun releaseJson(
    tag: String = "v2.0.0",
    body: String? = "\"그래프 열 폭 고정\"",
    assets: String = """
        {"name": "undine-2.0.0-macos.dmg", "browser_download_url": "https://x.invalid/dmg"},
        {"name": "undine-2.0.0-windows.msi", "browser_download_url": "https://x.invalid/msi"},
        {"name": "undine-2.0.0-linux.deb", "browser_download_url": "https://x.invalid/deb"},
        {"name": "checksums.txt", "browser_download_url": "https://x.invalid/checksums"}
    """.trimIndent(),
): String = buildString {
    append("""{"tag_name": "$tag",""")
    if (body != null) append(""" "body": $body,""")
    append(""" "assets": [$assets]}""")
}

/**
 * `packaging/RELEASE-CONTRACT.md` 해석. **실제 github.com 을 부르지 않는다** (결정 D3) —
 * 응답 문자열을 직접 만들어 계약 판정만 본다.
 */
class ReleaseContractSpec : FunSpec({

    test("자산 이름은 계약이 정한 OS 토큰과 확장자를 쓴다") {
        val version = AppVersion(1, 0, 0)

        assetNameFor(version, ReleaseOs.MACOS) shouldBe "undine-1.0.0-macos.dmg"
        assetNameFor(version, ReleaseOs.WINDOWS) shouldBe "undine-1.0.0-windows.msi"
        assetNameFor(version, ReleaseOs.LINUX) shouldBe "undine-1.0.0-linux.deb"
    }

    test("OS 이름은 세 토큰 중 하나로만 접힌다") {
        ReleaseOs.of("Mac OS X") shouldBe ReleaseOs.MACOS
        ReleaseOs.of("Windows 11") shouldBe ReleaseOs.WINDOWS
        ReleaseOs.of("Linux") shouldBe ReleaseOs.LINUX
        ReleaseOs.of("FreeBSD") shouldBe ReleaseOs.LINUX
    }

    test("checksums.txt 는 64자리 hex + 공백 둘 + 파일명으로 읽는다") {
        val text = "$MACOS_CHECKSUM  undine-1.0.0-macos.dmg\n$CHECKSUMS_CHECKSUM  undine-1.0.0-linux.deb\n"

        val parsed = parseChecksums(text).shouldNotBeNull()

        parsed["undine-1.0.0-macos.dmg"] shouldBe MACOS_CHECKSUM
        parsed["undine-1.0.0-linux.deb"] shouldBe CHECKSUMS_CHECKSUM
    }

    test("형식에서 벗어난 줄이 하나라도 있으면 계약 위반이다") {
        // 공백 하나 · 대문자 hex · 길이 부족 — 셋 다 계약 밖이다. 건너뛰면 검증이 조용히 사라진다.
        parseChecksums("$MACOS_CHECKSUM undine-1.0.0-macos.dmg").shouldBeNull()
        parseChecksums("${MACOS_CHECKSUM.uppercase()}  undine-1.0.0-macos.dmg").shouldBeNull()
        parseChecksums("abc  undine-1.0.0-macos.dmg").shouldBeNull()
        parseChecksums("").shouldBeNull()
    }

    test("계약대로 온 응답에서 이 OS 의 자산과 체크섬 목록을 고른다") {
        val release = parseLatestRelease(releaseJson(), ReleaseOs.MACOS).shouldNotBeNull()

        release.version shouldBe AppVersion(2, 0, 0)
        release.assetName shouldBe "undine-2.0.0-macos.dmg"
        release.assetUrl shouldBe "https://x.invalid/dmg"
        release.checksumsUrl shouldBe "https://x.invalid/checksums"
        release.releaseNotes shouldBe "그래프 열 폭 고정"
    }

    test("OS 마다 자기 자산을 고른다 — 이름이 비슷한 다른 자산을 고르지 않는다") {
        parseLatestRelease(releaseJson(), ReleaseOs.WINDOWS)?.assetUrl shouldBe "https://x.invalid/msi"
        parseLatestRelease(releaseJson(), ReleaseOs.LINUX)?.assetUrl shouldBe "https://x.invalid/deb"
    }

    test("내 OS 자산이 빠진 릴리즈는 계약 위반이다 — 다른 자산으로 대신하지 않는다") {
        val withoutMac = releaseJson(
            assets = """
                {"name": "undine-2.0.0-linux.deb", "browser_download_url": "https://x.invalid/deb"},
                {"name": "checksums.txt", "browser_download_url": "https://x.invalid/checksums"}
            """.trimIndent(),
        )

        parseLatestRelease(withoutMac, ReleaseOs.MACOS).shouldBeNull()
    }

    test("checksums.txt 가 없으면 계약 위반이다 — 검증 없이 받지 않는다") {
        val withoutChecksums = releaseJson(
            assets = """{"name": "undine-2.0.0-macos.dmg", "browser_download_url": "https://x.invalid/dmg"}""",
        )

        parseLatestRelease(withoutChecksums, ReleaseOs.MACOS).shouldBeNull()
    }

    test("태그 형식이 계약 밖이면 읽지 않는다") {
        parseLatestRelease(releaseJson(tag = "2.0.0"), ReleaseOs.MACOS).shouldBeNull()
        parseLatestRelease(releaseJson(tag = "v0.9.0"), ReleaseOs.MACOS).shouldBeNull()
        parseLatestRelease(releaseJson(tag = "release-2"), ReleaseOs.MACOS).shouldBeNull()
    }

    test("릴리즈 노트가 없으면 빈 노트다 — 계약이 다루지 않는 필드다") {
        val release = parseLatestRelease(releaseJson(body = null), ReleaseOs.MACOS).shouldNotBeNull()

        release.releaseNotes shouldBe ""
    }

    test("릴리즈 노트 자리에 문자열이 아닌 값이 오면 응답을 믿지 않는다") {
        parseLatestRelease(releaseJson(body = "42"), ReleaseOs.MACOS).shouldBeNull()
    }

    test("JSON 이 아니거나 객체가 아니면 계약 위반이다") {
        parseLatestRelease("응답 아님", ReleaseOs.MACOS).shouldBeNull()
        parseLatestRelease("[]", ReleaseOs.MACOS).shouldBeNull()
        parseLatestRelease("""{"tag_name": "v2.0.0"}""", ReleaseOs.MACOS).shouldBeNull()
    }
})
