package dev.undine.domain.externaltool

/**
 * diff 도구에 보여 줄 두 버전의 **내용**.
 *
 * 경로가 아니라 내용을 받는 이유는 비교 대상이 워킹트리 파일이라는 보장이 없기 때문이다
 * (커밋 사이 비교·스테이지 비교). 도구에 넘길 파일은 Gateway 가 자기 임시 파일로 만든다.
 */
data class DiffToolInput(val local: String, val remote: String)

/**
 * merge 도구에 넘길 3-way 입력과 편집 대상.
 *
 * [merged] 는 도구가 열어 고칠 **초깃값**이다 — 충돌 표식이 든 워킹트리 내용을 그대로 준다.
 * 종료 뒤 이 값과 달라졌는지로 "저장했다" 와 "그냥 닫았다" 를 가른다.
 *
 * [base] 는 공통 조상 내용이며 조상에 파일이 없던 충돌(양쪽 추가)은 빈 문자열이다 —
 * "조상에 없었다" 는 곧 "조상에서 비어 있었다" 라 별도 상태를 만들지 않는다.
 */
data class MergeToolInput(
    val local: String,
    val remote: String,
    val base: String,
    val merged: String,
)

/**
 * 외부 diff/merge 도구를 띄우는 계약. 구현은 `ExternalToolGatewayImpl` 이다.
 *
 * 도구 결정은 **Git 표준 설정이 먼저**다 (`diff.tool`·`merge.tool` 과 각각의
 * `difftool.<name>.cmd`·`mergetool.<name>.cmd`). 사용자가 이미 설정해 뒀다면 앱에서 또 설정하게
 * 만들지 않는다. 앱 설정(`Settings.externalTools`)은 Git 설정이 **없을 때만** 쓰는 차선값이다.
 *
 * 실행은 셸을 거치지 않는 **인자 배열**이다 — 셸을 거치면 공백이 든 경로가 쪼개지고,
 * 최악의 경우 경로에 든 문자가 명령으로 해석된다.
 *
 * **시간 제한을 두지 않는다.** 병합 도구는 대화형이라 몇십 분이 정상이며, 고정 제한은 사용자를
 * 병합 도중에 죽인다. 종료 경로는 호출자의 코루틴 취소뿐이고 취소는 그대로 전파된다.
 */
interface ExternalToolGateway {

    /** diff 도구를 띄우고 종료를 기다린다. */
    suspend fun openDiff(input: DiffToolInput): DiffToolResult

    /**
     * merge 도구를 띄우고 종료를 기다린 뒤 `$MERGED` 의 변경을 결과로 돌려준다.
     * 임시 파일은 비정상 종료·취소 경로에서도 정리된다.
     */
    suspend fun openMerge(input: MergeToolInput): MergeToolResult
}
