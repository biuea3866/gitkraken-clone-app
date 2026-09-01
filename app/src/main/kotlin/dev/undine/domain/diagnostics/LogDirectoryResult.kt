package dev.undine.domain.diagnostics

import java.nio.file.Path

/**
 * 로그 디렉터리가 **아직 없다**는 사실.
 *
 * 조회와 열기가 똑같이 마주치는 상태라 한 번만 정의하고 두 결과 타입의 하위 타입으로 삼는다 —
 * 같은 상태를 두 번 정의하면 한쪽만 늘어난다.
 *
 * 이것은 사고가 아니라 **예상되는 상태**다. 아직 아무 문제도 없었거나 사용자가 디렉터리를 지운
 * 것이므로 실패로 올리지 않는다. 화면은 이 값을 받으면 열기를 비활성으로 둔다.
 */
data object LogDirectoryMissing : LogDirectoryLocation, OpenLogDirectoryResult

/** 로그 디렉터리 조회 결과. */
sealed interface LogDirectoryLocation {

    /** 디렉터리가 [path] 에 있다. 조회는 경로를 **만들지 않는다** — 있는 것을 알려 줄 뿐이다. */
    data class Found(val path: Path) : LogDirectoryLocation
}

/** 파일 관리자 열기 결과. */
sealed interface OpenLogDirectoryResult {

    /** 파일 관리자에 열기를 넘겼다. */
    data object Opened : OpenLogDirectoryResult

    /**
     * 파일 관리자를 띄우지 못했다. [reason] 은 화면이 그대로 보여 줄 사유다.
     *
     * 조용한 성공으로 접지 않는다 — 아무 창도 열리지 않았는데 성공으로 보이면 사용자는 무엇이
     * 잘못됐는지 알 길이 없다.
     */
    data class OpenFailed(val reason: String) : OpenLogDirectoryResult
}
