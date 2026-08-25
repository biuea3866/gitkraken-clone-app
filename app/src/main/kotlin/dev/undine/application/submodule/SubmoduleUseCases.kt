package dev.undine.application.submodule

import dev.undine.domain.CommitResult
import dev.undine.domain.StagingGateway
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway

/** 서브모듈 목록을 presentation에 전달한다. */
class LoadSubmodulesUseCase(private val gateway: SubmoduleGateway) {
    suspend fun execute(): List<Submodule> = gateway.list()
}

/** 아직 초기화되지 않은 서브모듈을 비재귀로 초기화한다. */
class InitializeSubmoduleUseCase(private val gateway: SubmoduleGateway) {
    suspend fun execute(path: String) {
        gateway.initialize(path, recursive = false)
    }
}

/** 초기화된 서브모듈을 부모가 기록한 커밋으로 맞춘다. */
class UpdateSubmoduleUseCase(private val gateway: SubmoduleGateway) {
    suspend fun execute(path: String) {
        gateway.update(path, recursive = false)
    }
}

/**
 * 서브모듈 gitlink를 부모 저장소에 반영한다.
 *
 * `SubmoduleGateway`를 넓히지 않는다. gitlink도 부모의 경로 하나이므로, 확정 결정(E6)대로
 * 기존 스테이징·커밋 계약을 그대로 사용한다.
 */
class CommitSubmodulePointerUseCase(private val stagingGateway: StagingGateway) {
    suspend fun execute(path: String, message: String): CommitResult {
        stagingGateway.stage(listOf(path))
        return stagingGateway.commit(message)
    }
}
