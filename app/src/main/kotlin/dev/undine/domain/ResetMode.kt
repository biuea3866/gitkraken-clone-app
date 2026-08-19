package dev.undine.domain

/**
 * reset 모드. hard 는 워킹트리를 파괴하므로 이 enum 에 넣지 않고
 * `WorktreeOpsGateway.hardReset` 별도 메서드로 둔다 — 플래그로 실수로 켜지지 않게 한다.
 */
enum class ResetMode {
    SOFT,
    MIXED,
}
