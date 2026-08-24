package dev.undine.presentation.toolbar

/** 툴바가 시작할 수 있는 원격 작업. force push 는 별도 작업이 아니라 [PUSH] 의 확인된 변형이다. */
enum class RemoteOperation {
    FETCH,
    PULL,
    PUSH,
}
