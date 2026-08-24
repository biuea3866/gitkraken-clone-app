package dev.undine.presentation.sidebar

import androidx.compose.runtime.Immutable
import dev.undine.domain.Branch

/**
 * 확인을 기다리는 파괴적 동작. 확인 전에는 어떤 삭제도 실행되지 않는다.
 *
 * 삭제는 두 단계다 — 먼저 [DeleteBranch] 를 확인받아 **비강제** 삭제를 시도하고,
 * 저장소가 미병합을 이유로 거부하면 [ForceDeleteUnmerged] 로 올라가 결과(커밋 도달 불가)를
 * 알린 뒤 다시 확인받는다. 삭제는 reflog 로만 복구되므로 단계를 건너뛰지 않는다.
 */
@Immutable
sealed interface SidebarConfirmation {

    val branch: Branch

    data class DeleteBranch(override val branch: Branch) : SidebarConfirmation

    data class ForceDeleteUnmerged(override val branch: Branch) : SidebarConfirmation
}
