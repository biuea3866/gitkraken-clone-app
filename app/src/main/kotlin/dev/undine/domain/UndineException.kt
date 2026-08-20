package dev.undine.domain

/**
 * Undine 의 모든 도메인 실패는 이 계층 안에 있다.
 * sealed 라 presentation 이 `when` 으로 빠짐없이 사용자 메시지로 번역할 수 있다.
 * infrastructure 는 JGit 예외를 그대로 올리지 않고 여기로 번역한다.
 */
sealed class UndineException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 커밋 해시가 40자 hexadecimal 이 아니다. 검증은 [CommitId] 가 한다. */
    class InvalidCommitId(val raw: String) :
        UndineException("커밋 해시는 40자 hexadecimal 이어야 합니다: '$raw'")

    /** 참조 이름이 git ref 규칙에 맞지 않다. 형식 규칙은 `RefGateway` 소유 티켓이 구현한다. */
    class InvalidRefName(val raw: String) :
        UndineException("사용할 수 없는 참조 이름입니다: '$raw'")

    /**
     * 경로를 Git 저장소로 열 수 없다. 경로 유효성 판정은 `RepositoryGateway` 소유 티켓이 구현한다.
     *
     * [reason] 을 닫힌 enum 으로 두는 이유: 사용자가 취할 행동이 사유마다 다르다
     * (경로 확인 / 다른 디렉토리 선택 / 권한 부여). 자유 문자열이면 화면이 분기할 수 없다.
     */
    class InvalidRepositoryPath(val raw: String, val reason: Reason) :
        UndineException("Git 저장소로 열 수 없는 경로입니다(${reason.name}): '$raw'") {

        enum class Reason {
            /** 경로가 존재하지 않는다. */
            NOT_FOUND,

            /** 경로는 있지만 Git 저장소가 아니다. */
            NOT_A_REPOSITORY,

            /** 읽을 권한이 없다. */
            PERMISSION_DENIED,

            /** 베어 저장소다 — 워킹트리가 없어 이 앱이 다룰 수 없다. */
            BARE_REPOSITORY,
        }
    }

    /**
     * 원격 인증 실패. 원격 URL 에는 토큰이 들어 있을 수 있어 메시지에는 원격 이름만 담는다.
     */
    class AuthenticationFailed(val remote: String, cause: Throwable? = null) :
        UndineException("원격 '$remote' 인증에 실패했습니다.", cause)

    /** 병합·리베이스·revert 등에서 충돌이 발생했다. */
    class Conflict(val paths: List<String>) :
        UndineException("충돌이 발생했습니다: ${paths.size}개 파일")

    /** 현재 저장소 상태에서 허용되지 않는 연산이다 (예: 이미 병합된 브랜치 재병합). */
    class StateViolation(val detail: String) :
        UndineException("현재 저장소 상태에서 수행할 수 없습니다: $detail")

    /** 커밋되지 않은 변경 때문에 진행할 수 없다. */
    class DirtyWorkingTree(val paths: List<String>) :
        UndineException("워킹트리에 커밋되지 않은 변경이 있습니다: ${paths.size}개 파일")

    /**
     * 찾는 대상이 없다. 사용자가 오타를 냈거나 대상이 이미 사라진 경우다 —
     * 앱 버그인 [GitOperationFailed] 와 구분해야 화면이 다르게 안내할 수 있다.
     */
    class NotFound(val kind: Kind, val name: String) :
        UndineException("${kind.label} 을(를) 찾을 수 없습니다: '$name'") {

        enum class Kind(val label: String) {
            REF("참조"),
            COMMIT("커밋"),
            STASH("스태시"),
            REMOTE("원격"),
        }
    }

    /**
     * JGit 연산이 예상 못 한 이유로 실패했다. 사용자가 고칠 수 없는 실패이므로
     * 화면은 로그 위치를 안내하고 [cause] 를 보존한다.
     */
    class GitOperationFailed(val operation: String, cause: Throwable? = null) :
        UndineException("Git 연산 '$operation' 이 실패했습니다.", cause)

    /** git 작성자 정보(user.name·user.email)가 설정되지 않아 커밋할 수 없다. */
    class AuthorNotConfigured :
        UndineException("Git 작성자 정보가 설정되지 않았습니다. user.name 과 user.email 을 설정하세요.")

    /** 스테이징된 변경이 없어 커밋할 것이 없다. */
    class NothingToCommit :
        UndineException("스테이징된 변경이 없습니다.")
}
