package dev.undine.domain.externaltool

/**
 * 외부 프로세스 실행 경계.
 *
 * 인터페이스로 두는 이유는 **외부 도구가 없는 개발·CI 환경에서도 로직을 전수 검증**하기 위해서다
 * (wave 7 결정 D3) — 미설치·비정상 종료·오래 걸리는 실행·취소를 가짜 구현으로 재현한다.
 * 실제 프로세스 구현은 infrastructure 에 있다.
 */
interface ExternalToolRunner {

    /**
     * [executable] 을 지금 실행할 수 있는지 본다. 실행 **전에** 판정하려고 따로 둔다 —
     * 프로세스 시작 실패 메시지는 사용자에게 의미가 없다.
     */
    suspend fun isInstalled(executable: String): Boolean

    /**
     * [command] 를 **셸을 거치지 않고** 인자 배열 그대로 실행하고 종료 코드를 돌려준다.
     * 첫 원소가 실행 파일이다.
     *
     * 시간 제한을 두지 않는다. 호출자 코루틴이 취소되면 프로세스를 끝내고 취소를 전파한다.
     */
    suspend fun run(command: List<String>): Int
}
