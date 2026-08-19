package dev.undine.domain

/**
 * 저장소 열기·상태 조회·닫기. 구현은 infrastructure 의 `RepositoryGatewayImpl` 이다.
 *
 * 모든 Gateway 메서드는 `suspend` 다 — 어느 디스패처에서 돌릴지는 application 이 정한다.
 */
interface RepositoryGateway {

    suspend fun open(path: RepositoryPath): OpenedRepository

    suspend fun status(): WorkingTreeStatus

    suspend fun close()
}
