package dev.undine.presentation.welcome

import androidx.compose.runtime.Composable
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.welcome

/**
 * 화면에 띄울 안내 하나. `sealed` 라 [welcomeNoticeText] 의 `when` 이 새 종류를 조용히 삼키지 않는다
 * (kotlin-idioms 4항).
 *
 * **문구가 아니라 사유를 들고 있다** — 번역은 [welcomeNoticeText] 가 i18n 카탈로그에서 찾는다.
 * 원격 URL·자격증명은 어느 종류에도 담지 않는다: URL 에 토큰이 섞여 있을 수 있어
 * 안내 문구로도 로그로도 새어 나가면 안 된다 (`credential-handling` 2항).
 */
sealed interface WelcomeNotice {

    /** 로컬 열기 실패 — 사유마다 사용자가 취할 행동이 달라 [reason] 을 그대로 들고 있는다. */
    data class OpenFailed(val reason: UndineException.InvalidRepositoryPath.Reason) : WelcomeNotice

    /**
     * 네 가지 경로 사유로 설명되지 않는 열기 실패(`GitOperationFailed` 등).
     * 사용자가 고칠 수 없는 실패라 로그를 가리키는 문구를 쓴다.
     */
    data object OpenFailedUnexpectedly : WelcomeNotice

    /** 원격 인증 실패. 무엇을 확인할지(키체인·SSH 설정)만 말하고 원격을 특정하지 않는다. */
    data object AuthenticationFailed : WelcomeNotice

    /** 대상 디렉터리가 비어 있지 않아 clone 을 시작하지 않았다. 오류가 아니라 다시 고르면 되는 갈림길이다. */
    data object TargetNotEmpty : WelcomeNotice

    /** 인증 외의 이유로 clone 이 실패했다. */
    data object CloneFailed : WelcomeNotice

    /**
     * 실패·취소 뒤 앱이 만든 디렉터리를 지우지 못했다. [path] 는 사용자가 직접 지워야 하는
     * **로컬 경로**다 — 원격 URL 이 아니다.
     */
    data class CleanupFailed(val path: RepositoryPath) : WelcomeNotice
}

/** 안내를 현재 로케일 문구로 옮긴다. 문자열 하드코딩 없이 `welcome.*` 카탈로그만 쓴다. */
@Composable
fun welcomeNoticeText(notice: WelcomeNotice): String {
    val texts = strings.welcome
    return when (notice) {
        is WelcomeNotice.OpenFailed -> when (notice.reason) {
            UndineException.InvalidRepositoryPath.Reason.NOT_FOUND -> texts.errorNotFound
            UndineException.InvalidRepositoryPath.Reason.NOT_A_REPOSITORY -> texts.errorNotARepository
            UndineException.InvalidRepositoryPath.Reason.PERMISSION_DENIED -> texts.errorPermissionDenied
            UndineException.InvalidRepositoryPath.Reason.BARE_REPOSITORY -> texts.errorBareRepository
        }
        WelcomeNotice.OpenFailedUnexpectedly -> texts.errorOpenFailed
        WelcomeNotice.AuthenticationFailed -> texts.errorAuthentication
        WelcomeNotice.TargetNotEmpty -> texts.errorTargetNotEmpty
        WelcomeNotice.CloneFailed -> texts.errorCloneFailed
        is WelcomeNotice.CleanupFailed -> texts.cleanupFailed(notice.path.value)
    }
}
