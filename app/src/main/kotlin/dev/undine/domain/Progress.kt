package dev.undine.domain

/**
 * clone·fetch·push 처럼 초 단위로 걸리는 작업의 진행 상황.
 * [completedFraction] 은 0.0~1.0, [phase] 는 사용자에게 보여줄 단계명이다.
 */
data class Progress(
    val completedFraction: Double,
    val phase: String,
)
