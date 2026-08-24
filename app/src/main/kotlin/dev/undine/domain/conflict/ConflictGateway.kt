package dev.undine.domain.conflict

/** 충돌한 파일 1건. 이진 파일은 병합할 수 없어 화면이 선택지만 제공한다. */
data class ConflictedFile(val path: String, val isBinary: Boolean)

/**
 * 충돌 해결에 필요한 저장소 접근. 구현은 `ConflictGatewayImpl` 이다.
 *
 * 이 티켓이 자기 domain 패키지에 계약을 둔다 (결정문 A4·공통 규약 6) — 기존 최상위 계약을 고치지 않는다.
 *
 * **해결은 워킹트리와 인덱스를 함께 갱신한다.** 인덱스만 올리면 파일에는 표식이 남은 채 충돌이
 * 해결된 것으로 보이고, 워킹트리만 쓰면 계속(`continue`)이 미해결로 막힌다.
 */
interface ConflictGateway {

    /** 지금 충돌한 파일 목록. 충돌이 없으면 빈 목록이며 오류가 아니다. */
    suspend fun listConflicted(): List<ConflictedFile>

    /**
     * 표식이 든 워킹트리 파일 내용을 읽는다.
     *
     * 세 버전을 따로 주지 않는 이유는 사용자가 이미 손으로 고친 내용을 살려야 하기 때문이다 —
     * 인덱스 stage 로 3-way 를 재구성하면 그 편집이 사라진다.
     *
     * @throws dev.undine.domain.UndineException.NotFound 그 경로가 충돌 목록에 없을 때
     */
    suspend fun readConflicted(path: String): String

    /** 해결 결과를 워킹트리에 쓰고 인덱스에 올린다. */
    suspend fun resolve(path: String, content: String)

    /**
     * 이진 파일 충돌을 한쪽으로 확정한다. 내용을 합치지 않고 그 스테이지를 그대로 채택한다.
     *
     * @throws dev.undine.domain.UndineException.NotFound 채택할 스테이지가 없을 때
     *   (한쪽에서 삭제된 충돌은 그 쪽 스테이지가 없다)
     */
    suspend fun resolveBinary(path: String, side: ConflictSide)
}
