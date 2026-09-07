package dev.undine.infrastructure.git.lfs

import dev.undine.domain.lfs.LfsLock
import dev.undine.domain.lfs.LfsObject
import dev.undine.domain.lfs.LfsObjectState
import dev.undine.domain.lfs.LfsVersion

internal const val SUBCOMMAND_VERSION = "version"
internal const val SUBCOMMAND_LS_FILES = "ls-files"
internal const val SUBCOMMAND_LOCKS = "locks"
internal const val SUBCOMMAND_FETCH = "fetch"

/**
 * `git-lfs` 로 실행하는 하위 명령 **전부**.
 *
 * 이 환경에는 `git-lfs` 가 없어 실제 출력으로 인자와 형식을 검증할 수 없다. 그래서 쓰는 명령을
 * 목록으로 닫아 두고 늘리지 않는다 — 기억으로 플래그를 늘리는 것이 이 상황의 가장 큰 위험이다.
 * 추적 규칙은 여기에 없다: `.gitattributes` 를 직접 읽고 쓴다 (원문 보존 통제권을 잃지 않으려고).
 */
internal enum class LfsCommand(val arguments: List<String>) {

    /** 설치 확인. */
    VERSION(listOf(SUBCOMMAND_VERSION)),

    /** 객체 상태 — 포인터만 있는지 내려받았는지. */
    LS_FILES(listOf(SUBCOMMAND_LS_FILES)),

    /** 객체 상태 + 크기. 다운로드 전 합계 바이트를 알리는 데만 쓴다. */
    LS_FILES_WITH_SIZE(listOf(SUBCOMMAND_LS_FILES, "--size")),

    /** 잠금 조회. */
    LOCKS(listOf(SUBCOMMAND_LOCKS, "--json")),

    /** 객체 다운로드. */
    FETCH(listOf(SUBCOMMAND_FETCH)),
    ;

    /** 이 명령이 쓰는 하위 명령 이름. 허용 목록 검증이 읽는다. */
    val subcommand: String get() = arguments.first()
}

/** 이 티켓이 허용하는 하위 명령 전부. */
internal val ALLOWED_SUBCOMMANDS: Set<String> =
    setOf(SUBCOMMAND_VERSION, SUBCOMMAND_LS_FILES, SUBCOMMAND_LOCKS, SUBCOMMAND_FETCH)

private const val VERSION_PREFIX = "git-lfs/"

/** 내려받은 객체 표식. 그 밖의 표식(`-`)은 포인터만 있는 상태다. */
private const val DOWNLOADED_MARKER = "*"

/** `<oid> <*|-> <path>` — `*` 는 내려받음, `-` 는 포인터만. */
private val LS_FILES_LINE = Regex("""^(\S+) ([*-]) (.+)$""")

/** `<oid> <*|-> <path> (<size> <unit>)` — `--size` 가 덧붙이는 괄호 부분까지. */
private val LS_FILES_WITH_SIZE_LINE =
    Regex("""^(\S+) ([*-]) (.+) \((\d+(?:\.\d+)?) ([A-Za-z]{1,2})\)$""")

/** `git-lfs` 가 쓰는 십진 단위의 한 계단. */
private const val DECIMAL_STEP = 1_000L

/** 아는 단위만 바이트로 바꾼다. 모르는 단위를 0 으로 접으면 틀린 크기로 다운로드를 승인시킨다. */
private val SIZE_UNITS: Map<String, Long> = mapOf(
    "B" to 1L,
    "KB" to DECIMAL_STEP,
    "MB" to DECIMAL_STEP * DECIMAL_STEP,
    "GB" to DECIMAL_STEP * DECIMAL_STEP * DECIMAL_STEP,
    "TB" to DECIMAL_STEP * DECIMAL_STEP * DECIMAL_STEP * DECIMAL_STEP,
)

/** 두 `ls-files` 정규식이 공유하는 그룹 번호. 숫자를 코드에 흩뿌리지 않는다. */
private const val OBJECT_ID_GROUP = 1
private const val MARKER_GROUP = 2
private const val PATH_GROUP = 3
private const val SIZE_AMOUNT_GROUP = 4
private const val SIZE_UNIT_GROUP = 5

private const val LOCK_PATH = "path"
private const val LOCK_OWNER = "owner"
private const val LOCK_OWNER_NAME = "name"

/**
 * 잠금 API 를 구현하지 않은 원격에 대해 `git-lfs` 가 내는 문구.
 *
 * **이 문구가 나올 때만 미지원이다.** 그 밖의 0 이 아닌 종료는 미지원이 아니라 실패다 —
 * 조회 실패를 "잠금 없음" 으로 보여 주면 사용자는 잠금이 없다고 믿는다. 부재와 실패를 섞지 않는
 * 원칙([LfsResult.OutputUnrecognized])을 종료 코드에도 그대로 적용한다.
 *
 * 두 문구 모두 잠금을 명시적으로 가리키므로 일반 오류와 겹치지 않는다. 이 환경에는 `git-lfs` 가
 * 없어 **실제 출력으로 검증되지 않았다** — 어긋나면 미지원이 실패로 보고되며, 그 방향이 안전하다.
 */
private val LOCKING_UNSUPPORTED_MARKERS = listOf(
    "does not support the git lfs locking api",
    "does not support locking",
)

/**
 * `git-lfs` 출력 해석. **한 곳에 모으고, 인식하지 못한 출력은 `null` 로 돌려준다.**
 *
 * 모르는 줄을 건너뛰거나 빈 결과로 접지 않는다 — "객체 0건" 과 "출력을 못 읽었다" 는 다른 값이고,
 * 뒤를 앞으로 접으면 화면이 거짓말한다. 기대 형식은 위 정규식과 주석에 남겨 두었으며, 실제
 * `git-lfs` 로는 검증되지 않았다.
 */
internal object LfsCliOutput {

    /** 선두가 `git-lfs/` 로 시작하는 한 줄. */
    fun versionOf(output: String): LfsVersion? =
        output.lineSequence().map(String::trim).firstOrNull { line -> line.isNotEmpty() }
            ?.takeIf { line -> line.startsWith(VERSION_PREFIX) }
            ?.let(::LfsVersion)

    fun objectsOf(output: String): List<LfsObject>? = output.parseLines { line ->
        LS_FILES_LINE.matchEntire(line)?.let { match ->
            val (objectId, marker, path) = match.destructured
            LfsObject(objectId = objectId, path = path, state = stateOf(marker))
        }
    }

    fun sizedObjectsOf(output: String): List<SizedLfsObject>? = output.parseLines { line ->
        LS_FILES_WITH_SIZE_LINE.matchEntire(line)?.let { match ->
            val groups = match.groupValues
            bytesOf(amount = groups[SIZE_AMOUNT_GROUP], unit = groups[SIZE_UNIT_GROUP])?.let { bytes ->
                SizedLfsObject(
                    entry = LfsObject(
                        objectId = groups[OBJECT_ID_GROUP],
                        path = groups[PATH_GROUP],
                        state = stateOf(groups[MARKER_GROUP]),
                    ),
                    bytes = bytes,
                )
            }
        }
    }

    /** JSON 배열. 각 원소는 문자열 `path` 와 문자열 `owner.name` 을 반드시 갖는다. */
    @Suppress("ReturnCount")
    fun locksOf(output: String): List<LfsLock>? {
        if (output.isBlank()) return emptyList()
        val elements = (readJson(output) as? JsonValue.Elements)?.values ?: return null
        val locks = elements.map(::lockOf)
        return if (locks.any { lock -> lock == null }) null else locks.filterNotNull()
    }

    @Suppress("ReturnCount")
    private fun lockOf(element: JsonValue): LfsLock? {
        val members = (element as? JsonValue.Members)?.entries ?: return null
        val path = (members[LOCK_PATH] as? JsonValue.Text)?.value ?: return null
        val owner = (members[LOCK_OWNER] as? JsonValue.Members)?.entries?.get(LOCK_OWNER_NAME)
        val ownerName = (owner as? JsonValue.Text)?.value ?: return null
        return LfsLock(path = path, owner = ownerName)
    }

    /**
     * 실패 사유가 **서버의 잠금 미지원**을 말하고 있는가.
     *
     * 참일 때만 비활성으로 보고한다. 판정 근거는 [LOCKING_UNSUPPORTED_MARKERS] 뿐이며,
     * 근거가 없으면 실패로 남긴다.
     */
    fun declaresLockingUnsupported(detail: String): Boolean {
        val normalized = detail.lowercase()
        return LOCKING_UNSUPPORTED_MARKERS.any { marker -> normalized.contains(marker) }
    }

    private fun stateOf(marker: String): LfsObjectState =
        if (marker == DOWNLOADED_MARKER) LfsObjectState.DOWNLOADED else LfsObjectState.POINTER_ONLY

    private fun bytesOf(amount: String, unit: String): Long? {
        val factor = SIZE_UNITS[unit.uppercase()] ?: return null
        return amount.toDoubleOrNull()?.times(factor)?.toLong()
    }

    /** 빈 출력은 0건이다. 한 줄이라도 읽지 못하면 목록 전체를 실패로 만든다. */
    private fun <T> String.parseLines(parse: (String) -> T?): List<T>? {
        val parsed = lines()
            .filter { line -> line.isNotBlank() }
            .map { line -> parse(line.trimEnd('\r')) }
        return if (parsed.any { entry -> entry == null }) null else parsed.filterNotNull()
    }
}

/** 크기 조회에서만 쓰는 객체 + 바이트 쌍. 객체 상태 조회는 크기를 알지 못한다. */
internal data class SizedLfsObject(val entry: LfsObject, val bytes: Long)
