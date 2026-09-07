package dev.undine.domain.lfs

/**
 * Git LFS 조회·편집 계약. 구현은 `LfsGatewayImpl` 이다.
 *
 * **LFS 프로토콜을 자체 구현하지 않는다.** 서버 구현체마다 어긋나므로 설치된 `git-lfs` 를 부르는
 * 어댑터로 두고, 미설치는 [LfsResult.NotInstalled] 로 명확히 알린다 — LFS 저장소를 LFS 없이 열면
 * 포인터 텍스트만 보여 사용자가 "파일이 깨졌다" 고 오해한다.
 *
 * **추적 규칙만 CLI 를 거치지 않는다.** `.gitattributes` 를 직접 읽고 쓴다 — 원문 보존이 이 기능의
 * 본체인데 CLI 를 거치면 그 통제권을 잃는다. 그래서 세 메서드([trackedRules]·[track]·[untrack])는
 * `git-lfs` 설치 여부와 무관하게 동작하고 [LfsResult] 를 쓰지 않는다.
 *
 * **잠금 생성·해제는 제공하지 않는다.** 서버 상태를 바꾸는 파괴적 연산인데 실패 경로(중복 잠금,
 * 남의 잠금 강제 해제, 네트워크 중단 시 불일치)가 설계돼 있지 않다 — 설계 없이 만들지 않는다.
 */
interface LfsGateway {

    /** `git-lfs` 가 설치돼 있는지와 그 버전. */
    suspend fun installation(): LfsResult<LfsVersion>

    /** `.gitattributes` 에 선언된 LFS 추적 규칙. 파일이 없으면 규칙 0건이다. */
    suspend fun trackedRules(): List<LfsTrackingRule>

    /**
     * [pattern] 추적 규칙을 더하고 갱신된 규칙 목록을 돌려준다. 이미 있으면 파일을 바꾸지 않는다.
     *
     * 대상 행만 더하고 나머지 행의 구분자·마지막 개행 유무는 그대로 둔다.
     *
     * @throws IllegalArgumentException 공백·탭·줄바꿈이 섞였거나 빈 패턴인 경우. 조용히 다듬지
     *   않는다 — 다듬으면 행 구조가 바뀌어 같은 문자열로 [untrack] 해도 원상 복구되지 않는다.
     */
    suspend fun track(pattern: String): List<LfsTrackingRule>

    /**
     * [pattern] 추적 규칙을 빼고 갱신된 규칙 목록을 돌려준다.
     *
     * **규칙이 0건이 되어도 `.gitattributes` 를 삭제하지 않는다** — 0바이트로 남긴다.
     * 지울지 말지를 가르려면 "이 파일을 우리가 만들었는가" 를 기억해야 하는데, 그 기억은 저장소
     * 전환·인스턴스 재생성·사용자의 외부 편집 앞에서 틀리고, 틀리면 **남의 파일을 지운다.**
     * 남는 빈 파일은 사용자가 지울 수 있지만 지운 사용자 파일은 되돌릴 수 없다.
     *
     * @throws IllegalArgumentException [track] 과 같은 조건. 거부된 입력은 파일을 건드리지 않는다.
     */
    suspend fun untrack(pattern: String): List<LfsTrackingRule>

    /** 워킹트리의 LFS 객체 상태. 포인터만 있는 것과 내려받아진 것을 구분해 보고한다. */
    suspend fun objects(): LfsResult<List<LfsObject>>

    /**
     * 아직 내려받지 않은 객체의 목록과 합계 바이트를 조회한다. **아무것도 내려받지 않는다.**
     *
     * 크기를 읽지 못하면 빈 목록이 아니라 실패로 보고해 다운로드를 막는다 — 틀린 크기를 보여 주고
     * 받게 하는 것보다 낫다.
     */
    suspend fun pendingDownload(): LfsResult<LfsDownloadEstimate>

    /**
     * 객체를 실제로 내려받는다. [pendingDownload] 와 **별도 호출**이다.
     *
     * 승인 콜백을 계약에 두지 않는다 — Gateway 가 UI 흐름을 알게 되고 "사용자가 거절함" 이 실패
     * 값으로 섞여 들어온다. **거절은 이 메서드를 부르지 않는 것**이라 Gateway 에 도달하지 않고,
     * **취소는 도는 중의 코루틴 취소**다. 두 개념이 같은 자리에서 만나지 않는다.
     *
     * [onProgress] 는 CLI 표준 출력 한 줄을 그대로 받는다 (퍼센트로 해석하지 않는다).
     */
    suspend fun download(onProgress: (String) -> Unit): LfsResult<Unit>

    /** 서버의 잠금 지원 여부와 현재 잠금 목록. */
    suspend fun locks(): LfsResult<LfsLockState>
}
