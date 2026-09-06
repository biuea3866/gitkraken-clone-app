package dev.undine.presentation.patch

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * OS 파일 드롭의 판정 결과.
 *
 * 거부는 **실패가 아니라 안내**다 — 예외를 던지지 않고 화면 상태로 표시한다. 첫 파일만 조용히
 * 받으면 사용자가 고르지 않은 패치가 적용된다.
 */
sealed interface PatchDrop {

    data class Accepted(val path: Path) : PatchDrop

    data class Rejected(val reason: Reason) : PatchDrop

    /** 왜 받지 않았는가. 자유 문자열이 아니라 닫힌 목록이라 화면이 사유마다 다르게 안내할 수 있다. */
    enum class Reason {

        /** 드롭에 파일이 없다 (텍스트·이미지 등). */
        NO_FILE,

        /** 파일이 여럿이다. 무엇을 적용할지 정할 수 없다. */
        MULTIPLE_FILES,
    }
}

/**
 * 드롭에서 꺼낸 경로 후보를 판정한다.
 *
 * **modifier 밖의 순수 함수**다 — 실제 OS 드래그 없이 테스트할 수 있어야 하고, 거부 판정이 Compose
 * 노드 안에 갇히면 그 판단을 확인할 길이 없다.
 *
 * @param locations Compose 데스크톱이 준 파일 위치 문자열. `file:` URI 와 평범한 경로를 모두 받는다.
 */
fun patchDropOf(locations: List<String>): PatchDrop {
    val paths = locations.mapNotNull(::toPathOrNull)
    return when {
        paths.isEmpty() -> PatchDrop.Rejected(PatchDrop.Reason.NO_FILE)
        paths.size > 1 -> PatchDrop.Rejected(PatchDrop.Reason.MULTIPLE_FILES)
        else -> PatchDrop.Accepted(paths.single())
    }
}

/**
 * `file:` URI 는 URI 로, 스킴이 없으면 경로 문자열로 읽는다.
 *
 * 다른 스킴(`http:` 등)은 **파일이 아니다** — 로컬 경로로 억지로 바꾸면 존재하지 않는 파일을 고른
 * 것처럼 보이고, 그 실패가 읽기 단계에서야 드러난다.
 */
private fun toPathOrNull(location: String): Path? = when {
    location.isBlank() -> null
    else -> when (schemeOf(location)) {
        null -> Paths.get(location)
        FILE_SCHEME -> Paths.get(URI(location))
        else -> null
    }
}

/** URI 문법에 맞지 않는 값(Windows 경로 등)은 스킴이 없는 것으로 본다 — 그냥 경로다. */
private fun schemeOf(location: String): String? = try {
    URI(location).scheme
} catch (ignored: URISyntaxException) {
    null
}

private const val FILE_SCHEME = "file"
