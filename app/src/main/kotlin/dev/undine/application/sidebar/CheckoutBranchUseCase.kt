package dev.undine.application.sidebar

import dev.undine.domain.RefGateway
import dev.undine.domain.RefName

/**
 * 사이드바에서 고른 참조로 체크아웃한다.
 *
 * **강제 체크아웃은 하지 않는다** — 워킹트리에 커밋되지 않은 변경이 있으면 덮어쓰지 않고
 * `DirtyWorkingTree` 로 실패해 화면이 사용자에게 알린다.
 */
class CheckoutBranchUseCase(private val refGateway: RefGateway) {

    suspend operator fun invoke(ref: RefName) = refGateway.checkout(ref, force = false)
}
