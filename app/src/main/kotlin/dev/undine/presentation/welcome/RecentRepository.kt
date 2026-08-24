package dev.undine.presentation.welcome

import androidx.compose.runtime.Immutable
import dev.undine.domain.RepositoryPath

/**
 * 최근 목록의 한 항목.
 *
 * [available] 은 **표시 직전에 파일 시스템으로 판정한 값**이다 — 설정에 저장된 사실이 아니다.
 * 사라진 경로를 목록에서 조용히 지우지 않고 회색으로 남기기 위해, 판정 시점이 저장 시점이 아니라
 * 표시 시점이어야 한다 (외장 디스크가 빠진 동안 목록이 비면 사용자가 복구할 방법이 없다).
 */
@Immutable
data class RecentRepository(
    val path: RepositoryPath,
    val available: Boolean,
)
