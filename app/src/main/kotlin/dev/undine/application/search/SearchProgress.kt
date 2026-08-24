package dev.undine.application.search

import dev.undine.domain.Commit

/**
 * 검색 순회가 흘려보내는 사건 — 찾은 커밋과 훑은 양.
 *
 * 결과만 흘리면 화면은 "얼마나 훑었는지" 를 알 수 없어 진행 표시를 글자로만 하게 된다. 순회 진행량을
 * 함께 내보내 화면이 진행 막대를 그릴 수 있게 한다 (compose-ui 7).
 */
sealed interface SearchProgress {

    /** 조건에 맞는 커밋을 찾았다. 전체 순회가 끝나기 전에도 도착한다. */
    data class Match(val commit: Commit) : SearchProgress

    /**
     * 페이지 하나를 다 훑었고 **뒤에 페이지가 더 남았다.** 순회가 끝나는 마지막 페이지에서는 나오지
     * 않는다 — 끝은 흐름의 종료가 알린다.
     *
     * @property scannedCommits 순회 시작부터 지금까지 훑은 커밋 수.
     * @property estimatedTotalCommits **다음 페이지가 마지막이라고 가정한** 총량. 전체 커밋 수는 다
     *   훑기 전에는 알 수 없다 — 그것을 아는 것이 곧 전체 순회다. 추정은 늘 실제 총량 이하라 진행률을
     *   낙관적으로 말하지만, 남은 페이지가 있는 동안 1.0 에 닿지 않고 [scannedCommits] 가 늘수록
     *   단조 증가한다.
     */
    data class Scanned(val scannedCommits: Int, val estimatedTotalCommits: Int) : SearchProgress
}
