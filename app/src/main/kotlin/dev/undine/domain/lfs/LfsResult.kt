package dev.undine.domain.lfs

/**
 * `git-lfs` CLI 를 거치는 조회의 결과.
 *
 * 다섯 갈래를 **끝까지 다른 값으로** 둔다 — 사용자가 취할 행동이 전부 다르기 때문이다.
 * 특히 [NotInstalled] 와 [StartFailed] 를 합치지 않는다: 앞은 설치하면 풀리는 문제이고,
 * 뒤는 실행 권한·작업 디렉터리처럼 환경이 잘못된 문제다.
 *
 * [OutputUnrecognized] 도 빈 결과로 접지 않는다 — "객체 0건" 과 "출력을 못 읽었다" 는 다르고,
 * 뒤를 앞으로 접으면 화면이 거짓말한다.
 */
sealed interface LfsResult<out T> {

    data class Available<T>(val value: T) : LfsResult<T>

    /** 실행 파일을 찾지 못했다. LFS 저장소는 포인터 텍스트로만 보인다는 사실을 화면이 알려야 한다. */
    data object NotInstalled : LfsResult<Nothing>

    /** 실행 파일은 있으나 프로세스를 띄우지 못했다 (실행 권한 거부·잘못된 작업 디렉터리 등). */
    data class StartFailed(val detail: String) : LfsResult<Nothing>

    /** 프로세스가 0 이 아닌 코드로 끝났다. [detail] 은 표준 오류 원문이다. */
    data class CommandFailed(val exitCode: Int, val detail: String) : LfsResult<Nothing>

    /**
     * 프로세스는 정상 종료했으나 출력을 **기대한 형식으로 읽지 못했다.**
     *
     * 관대한 파서로 모르는 줄을 건너뛰지 않는다. 이 환경에는 `git-lfs` 가 없어 실제 출력으로
     * 형식을 검증할 수 없으므로, 어긋나면 조용히 접는 대신 여기로 올려 드러낸다.
     */
    data class OutputUnrecognized(val output: String) : LfsResult<Nothing>
}
