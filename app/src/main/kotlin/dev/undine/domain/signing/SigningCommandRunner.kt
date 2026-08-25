package dev.undine.domain.signing

/**
 * 서명·검증 프로그램을 실행하는 **프로세스 경계**.
 *
 * 이 경계를 계약으로 뽑아 두는 이유는 두 가지다.
 * 1. 서명 키는 앱이 아니라 `gpg-agent`/`ssh-agent` 가 들고 있다. 앱은 프로그램을 부를 뿐
 *    키도 패스프레이즈도 만지지 않는다.
 * 2. `gpg`·`ssh-keygen` 이 없는 개발·CI 환경에서도 서명 판단 로직 전체가 검증돼야 한다.
 *    가짜 구현을 끼우면 미설치·비정상 종료·정상 출력을 전부 재현할 수 있다.
 */
interface SigningCommandRunner {

    /**
     * [command] 를 실행하고 [standardInput] 을 표준 입력으로 넣는다.
     *
     * [command] 는 **셸을 거치지 않는 인자 배열**이다 — 경로에 공백이나 따옴표가 들어가도
     * 그대로 전달되고, 사용자 설정값이 셸 명령으로 해석될 여지가 없다.
     */
    suspend fun run(command: List<String>, standardInput: ByteArray): SigningCommandResult
}

/** [SigningCommandRunner.run] 의 결과. */
sealed interface SigningCommandResult {

    /**
     * 프로그램이 끝까지 실행됐다. [exitCode] 가 0 이 아닌 것도 여기다 — 실행 자체는 됐기 때문이다.
     *
     * [standardError] 를 남기는 이유는 실패 사유를 사용자에게 전할 근거가 거기에만 있어서다.
     * 이 경로에 비밀이 흐르지 않는 이유는 앱이 패스프레이즈를 아예 다루지 않기 때문이다.
     */
    data class Completed(
        val exitCode: Int,
        val standardOutput: String,
        val standardError: String,
    ) : SigningCommandResult

    /** 실행 파일을 찾거나 실행할 수 없었다 — 도구가 설치돼 있지 않은 경우가 대표적이다. */
    data class NotExecutable(val program: String) : SigningCommandResult

    /**
     * 시도는 시작했지만 정상적으로 끝나지 못했다 (시간 초과·중단, 서명에 필요한 파일 입출력 실패).
     *
     * 이 경우를 예외로 올리지 않는 이유는 [SigningGateway.sign] 이 "실패는 던지지 않는다" 를
     * 계약으로 걸어 뒀기 때문이다 — [detail] 이 사용자에게 전할 사유를 담는다.
     */
    data class Interrupted(val detail: String) : SigningCommandResult
}
