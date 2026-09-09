package dev.undine.domain.update

/** 릴리즈 태그 앞에 붙는 글자. `v1.0.0` 의 `v` 다 (`packaging/RELEASE-CONTRACT.md` "조회 좌표"). */
private const val TAG_PREFIX = "v"

/** `MAJOR.MINOR.PATCH` 만 받는다. 접미사(`-rc1`)를 허용하면 계약에 없는 태그를 릴리즈로 읽는다. */
private val VERSION_PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)""")

/**
 * 릴리즈 태그가 담는 버전.
 *
 * **`MAJOR` 는 1 이상이어야 한다.** macOS jpackage 가 major 0 을 거부해 dmg 자체가 만들어지지
 * 않으므로, `v0.x.y` 는 발행될 수 없는 태그다 (`packaging/RELEASE-CONTRACT.md`). 그런 값을 파싱에
 * 통과시키면 클라이언트가 존재할 수 없는 릴리즈를 "새 버전" 으로 안내한다.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    /** 자산 이름에 들어가는 `<version>` 표기. 태그의 `v` 는 붙지 않는다. */
    override fun toString(): String = "$major.$minor.$patch"

    companion object {

        /** 계약이 요구하는 최소 major. */
        const val MINIMUM_MAJOR: Int = 1

        /**
         * `MAJOR.MINOR.PATCH` 를 읽는다. 계약 형식이 아니면 `null` 이다 —
         * **추측해 보완하지 않는다**: 틀린 버전으로 비교하면 엉뚱한 파일을 "새 버전" 으로 설치한다.
         */
        fun parse(raw: String): AppVersion? {
            val match = VERSION_PATTERN.matchEntire(raw.trim()) ?: return null
            val numbers = match.groupValues.drop(1).map(String::toIntOrNull)
            return if (numbers.any { it == null }) {
                null
            } else {
                AppVersion(
                    major = requireNotNull(numbers[0]),
                    minor = requireNotNull(numbers[1]),
                    patch = requireNotNull(numbers[2]),
                ).takeIf { it.major >= MINIMUM_MAJOR }
            }
        }

        /** 릴리즈 태그(`vX.Y.Z`)를 읽는다. `v` 가 없으면 계약 위반이라 `null` 이다. */
        fun parseTag(raw: String): AppVersion? {
            val trimmed = raw.trim()
            return if (trimmed.startsWith(TAG_PREFIX)) parse(trimmed.removePrefix(TAG_PREFIX)) else null
        }
    }
}
