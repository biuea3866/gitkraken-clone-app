package dev.undine.presentation.toolbar

import dev.undine.application.toolbar.FastForwardBranchUseCase
import dev.undine.application.toolbar.FetchRemoteUseCase
import dev.undine.application.toolbar.PullRemoteUseCase
import dev.undine.application.toolbar.PushRemoteUseCase

/**
 * 원격 작업 화면이 쓰는 UseCase 묶음 (`ConflictActions`·`StagingActions` 와 같은 방식).
 *
 * 툴바의 현재 브랜치 조작과 사이드바의 지목 조작이 **같은 묶음**을 쓴다 — 진입점마다 다른 경로를
 * 주면 두 경로의 안전 기준이 갈린다 (결정 D2).
 *
 * [fastForwardBranch] 만 지목 전용이다. 나머지 셋은 툴바가 이미 쓰던 것이고, 지목 push 는
 * [pushRemote] 에 그 브랜치의 참조를 넘길 뿐 새 경로를 만들지 않는다.
 */
class RemoteActions(
    val fetchRemote: FetchRemoteUseCase,
    val pullRemote: PullRemoteUseCase,
    val pushRemote: PushRemoteUseCase,
    val fastForwardBranch: FastForwardBranchUseCase,
)
