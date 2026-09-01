package dev.undine.domain.diagnostics

import java.nio.file.Path

/**
 * 플랫폼 파일 관리자 실행 경계.
 *
 * 인터페이스로 두는 이유는 외부 도구 실행 경계와 같다 —
 * **데스크톱 환경이 없는 개발·CI 에서도 로직을 전수 검증**하기 위해서다. 테스트가 개발자의
 * Finder·탐색기를 실제로 여는 일도 막는다. 실제 플랫폼 호출은 infrastructure 에 있다.
 */
interface FileManagerLauncher {

    /**
     * [directory] 를 파일 관리자에서 연다. 창이 열릴 때까지가 아니라 **띄우기를 넘길 때까지**만
     * 기다린다.
     *
     * 띄우지 못하면 사유가 담긴 `IOException` 을 던진다 — 열리지 않은 것을 성공으로 접지 않는다.
     */
    suspend fun open(directory: Path)
}
