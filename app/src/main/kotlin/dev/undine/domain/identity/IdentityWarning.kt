package dev.undine.domain.identity

/**
 * 커밋하기 **전에** 사용자에게 알릴 신원 문제.
 *
 * 잘못된 이메일로 쌓인 커밋은 되돌리는 비용이 크다 — 그래서 커밋을 막는 대신 미리 알린다.
 * sealed 라 화면이 `when` 으로 빠짐없이 번역할 수 있고, 판단할 수 없는 항목은 **경고를 만들지
 * 않는다**(경고 없음이지 실패가 아니다).
 */
sealed interface IdentityWarning {

    /**
     * 이 저장소에 쓸 프로필이 정해지지 않았다.
     *
     * 로컬 설정이 **이미 삭제된 프로필 이름**을 가리키는 경우도 여기로 온다 — 새 실패 상태를
     * 만들지 않고 "정해지지 않음" 과 같게 다룬다.
     */
    data object ProfileNotAssigned : IdentityWarning

    /** 원격 호스트가 프로필의 예상 호스트와 다르다 (회사 저장소에 개인 계정 등). 값은 정규화된 호스트다. */
    data class HostMismatch(val expectedHost: String, val remoteHost: String) : IdentityWarning

    /**
     * 최근 이력에 프로필과 다른 **author** 이메일이 있다.
     *
     * committer 는 보지 않는다 — rebase·cherry-pick 이 남긴 committer 까지 보면 오경고가 난다.
     */
    data class EmailMismatch(val profileEmail: String, val otherEmails: List<String>) : IdentityWarning
}
