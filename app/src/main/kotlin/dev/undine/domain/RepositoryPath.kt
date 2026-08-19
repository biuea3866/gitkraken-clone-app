package dev.undine.domain

/**
 * 로컬 Git 저장소 경로.
 *
 * 이 티켓에서는 **검증 없는 값 래퍼**다. 경로 유효성(존재·`.git` 보유·베어 여부)은 파일 시스템
 * 접근이 필요해 값 생성 시점에 판정할 수 없다. 판정은 `RepositoryGateway` 소유 티켓이
 * [UndineException.InvalidRepositoryPath] 와 함께 구현한다.
 */
@JvmInline
value class RepositoryPath(val value: String) {

    override fun toString(): String = value
}
