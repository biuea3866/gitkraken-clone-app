package dev.undine.domain.submodule

import dev.undine.domain.UndineException

/**
 * 서브모듈 하나의 상태. **세 축은 독립이다.**
 *
 * "미초기화 / 최신 / 수정됨 / 어긋남" 을 하나의 enum 으로 접으면 정보가 사라진다 —
 * 서브모듈 안에 커밋되지 않은 변경이 있으면서 **동시에** 부모가 기록한 커밋과 어긋날 수 있고,
 * 그때 사용자가 해야 할 일은 둘을 합친 것이다(커밋하거나 되돌린 뒤 업데이트).
 * 우선순위를 매겨 하나만 보고하면 나머지 하나를 화면이 영영 알려주지 못한다.
 *
 * "최신" 은 별도의 값이 아니라 [initialized] 가 true 이고 나머지 둘이 false 인 조합이다.
 *
 * @param initialized clone 되어 HEAD 를 가진 상태인지. false 면 나머지 두 축은 판정 대상이 아니다.
 * @param locallyModified 서브모듈 워킹트리·인덱스에 커밋되지 않은 변경이 있는지.
 *   중첩 서브모듈의 상태는 여기에 섞지 않는다 — 그것은 그 서브모듈 자신의 상태다.
 * @param divergedFromRecorded 부모가 기록한 커밋과 서브모듈의 실제 HEAD 가 다른지.
 */
data class SubmoduleState(
    val initialized: Boolean,
    val locallyModified: Boolean,
    val divergedFromRecorded: Boolean,
)

/**
 * 부모 저장소가 아는 서브모듈 하나.
 *
 * @param path 부모 워킹트리 기준 상대 경로. 서브모듈을 가리키는 식별자다.
 * @param url `.gitmodules` 가 선언한 원격 주소. 선언이 없으면 null 이다.
 */
data class Submodule(
    val path: String,
    val url: String?,
    val state: SubmoduleState,
)

/**
 * 서브모듈 조회·초기화·업데이트·추가·제거 계약. 구현은 `SubmoduleGatewayImpl` 이다.
 *
 * **재귀 기본값은 비재귀다.** 중첩 서브모듈까지 처리하면 clone 이 연쇄로 일어나 느리고 네트워크를
 * 많이 쓴다 — 사용자가 명시적으로 요구할 때만 내려간다.
 *
 * **[remove] 의 판단 기준은 "지울 수 있는가" 가 아니라 "보존해야 할 것이 있는가" 다.** 되돌릴 수 없는
 * 연산이라 개별 검사를 늘려 가는 접근은 빠진 검사 하나가 곧 데이터 유실이 된다 — 보존 대상을 열거하고,
 * 열거된 것이 하나라도 있으면 거부한다.
 */
interface SubmoduleGateway {

    /**
     * 부모 인덱스가 아는 서브모듈 전부를 경로 순으로 준다.
     * 서브모듈이 없으면 빈 목록이다 — 그것이 실패가 아니라 정상 결과다.
     */
    suspend fun list(): List<Submodule>

    /**
     * [path] 의 서브모듈을 쓸 수 있는 상태로 만든다 — 원격 주소를 저장소 설정에 등록하고,
     * 아직 없으면 clone 한 뒤 부모가 기록한 커밋으로 체크아웃한다. 이미 초기화돼 있으면 멱등이다.
     *
     * @param recursive true 면 그 서브모듈이 가진 중첩 서브모듈까지 같은 방식으로 초기화한다.
     * @throws UndineException.NotFound 그 경로의 서브모듈이 없을 때
     */
    suspend fun initialize(path: String, recursive: Boolean = false)

    /**
     * 이미 초기화된 [path] 의 서브모듈을 부모가 기록한 커밋으로 맞춘다.
     *
     * **초기화는 하지 않는다** — 미초기화 서브모듈을 조용히 clone 하면 사용자가 요구하지 않은
     * 네트워크 왕복이 일어난다. 그 경우는 [initialize] 가 할 일이다.
     *
     * @param recursive true 면 중첩 서브모듈에도 같은 규칙으로 적용한다. 그때도 초기화는 하지 않으므로
     *   미초기화된 중첩 서브모듈은 그대로 남는다 ([list] 로 확인할 수 있다).
     * @throws UndineException.NotFound 그 경로의 서브모듈이 없을 때
     * @throws UndineException.StateViolation 대상이 초기화되지 않았을 때
     */
    suspend fun update(path: String, recursive: Boolean = false)

    /**
     * [url] 의 저장소를 [path] 에 서브모듈로 붙이고 그 결과 상태를 준다.
     *
     * 실패하면 `.gitmodules` 항목과 이 호출이 만든 디렉터리를 되돌린다 — 절반만 붙은 저장소를
     * 남기지 않는다.
     *
     * @param branch `.gitmodules` 에 기록할 추적 브랜치. null 이면 기록하지 않는다.
     */
    suspend fun add(url: String, path: String, branch: String? = null): Submodule

    /**
     * [path] 의 서브모듈을 부모에서 떼어낸다 — 저장소 설정 · `.gitmodules` 선언 · 인덱스 gitlink 를
     * 지운 뒤 그 서브모듈이 차지하던 파일을 지운다.
     *
     * **먼저 보존해야 할 것을 전부 모은다.** 대상 아래에서 저장소가 되돌려줄 수 없는 것 —
     * 커밋되지 않은 변경 · 추적되지 않은 파일 · **무시된 파일** · 중첩 서브모듈의 같은 것들 ·
     * 유효한 저장소로 열리지 않는 **판정 불가** 경로 — 이 하나라도 있으면 [confirmed] 와 무관하게
     * 그 목록과 함께 거부한다. 판정 불가는 "깨끗함" 이 아니다 — 모르면 지우지 않는다.
     *
     * 삭제 대상은 그 스캔이 **열거한 경로로 한정**한다. "대상 아래 전부" 라는 재귀 삭제를 쓰지 않는다.
     * 삭제 앞의 되돌릴 수 있는 단계(설정·`.gitmodules`·인덱스)가 실패하면 호출 전 상태로 되돌리고
     * 파일은 건드리지 않는다.
     *
     * 검사와 실행 사이에 **다른 프로세스**가 워킹트리를 바꾸는 상황은 방어하지 않는다 — 명시적
     * 비목표이며 `git` 자신도 같은 계약이다.
     *
     * @param confirmed 사용자가 되돌릴 수 없는 연산임을 확인했는지. false 면 보존할 것이 없어도
     *   수행하지 않는다.
     * @throws UndineException.NotFound 그 경로의 서브모듈이 없을 때
     * @throws UndineException.StateViolation 보존 대상이 있을 때, 경로가 기준 디렉터리를 벗어날 때,
     *   또는 [confirmed] 가 false 일 때
     */
    suspend fun remove(path: String, confirmed: Boolean)
}
