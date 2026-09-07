package dev.undine.domain.lfs

import java.nio.file.Path

/**
 * `git-lfs` 프로세스 실행 경계.
 *
 * interface 로 두는 이유는 [dev.undine.domain.externaltool.ExternalToolRunner] 와 같다 —
 * **이 개발·CI 환경에는 `git-lfs` 가 없고**, 있는지 여부가 테스트 결과를 바꾸면 안 된다.
 * 미설치·시작 실패·비정상 종료·대량 출력·취소를 대역으로 재현한다.
 *
 * 실행 파일 이름 해소도 이 경계 **안**에서 한다 — 테스트가 전역 `PATH` 를 건드리지 않아야 한다.
 */
interface LfsCommandRunner {

    /**
     * [arguments] 를 `git-lfs` 하위 명령으로 실행한다. 셸을 거치지 않고 인자 배열 그대로 넘긴다.
     *
     * 표준 출력은 **기다리기 전에** 비운다 — 아무도 읽지 않는 파이프가 차면 자식이 쓰기에서 멈춘다.
     * [onOutputLine] 은 비우는 동안 표준 출력 한 줄씩을 그대로 받는다. 진행률을 퍼센트로 해석하지
     * 않는 이유는 `git lfs fetch` 의 진행 출력 형식이 안정적이라고 볼 근거가 없기 때문이다.
     *
     * 시간 제한을 두지 않는다. 호출자 코루틴이 취소되면 프로세스와 그 자손을 끝내고 취소를 전파한다.
     */
    suspend fun run(
        arguments: List<String>,
        workingDirectory: Path,
        onOutputLine: (String) -> Unit = {},
    ): LfsCommandOutcome
}

/** 프로세스 실행 자체의 결과. 출력 해석은 호출자(Gateway)가 한다. */
sealed interface LfsCommandOutcome {

    data class Completed(
        val exitCode: Int,
        val standardOutput: String,
        val standardError: String,
    ) : LfsCommandOutcome

    /** 실행 파일을 어디에서도 찾지 못했다. */
    data object NotInstalled : LfsCommandOutcome

    /** 실행 파일은 찾았으나 프로세스를 시작하지 못했다. */
    data class StartFailed(val detail: String) : LfsCommandOutcome
}
