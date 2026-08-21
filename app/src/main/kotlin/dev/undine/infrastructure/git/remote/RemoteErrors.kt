package dev.undine.infrastructure.git.remote

import dev.undine.domain.UndineException
import org.eclipse.jgit.errors.UnsupportedCredentialItem

/**
 * 위임할 자격증명 제공자(credential helper · ssh-agent)를 찾지 못했다.
 *
 * 앱에는 비밀번호 입력란이 없으므로 이 상태에서 인증을 완성할 방법이 없다.
 * 익명 접근으로 떨어지지 않고 여기서 멈춘 뒤 [RemoteErrors] 가
 * [UndineException.AuthenticationFailed] 로 번역한다.
 */
internal class CredentialProviderUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 원격 주소를 지운 원인. JGit 예외를 그대로 cause 로 달면 스택트레이스에 토큰과 주소가 남는다.
 * 원인 사슬의 메시지만 마스킹해 옮기고 스택트레이스는 보존한다.
 */
internal class MaskedRemoteCause(message: String) : Exception(message)

/** JGit 원격 실패를 도메인 예외로 번역한다. 번역 과정에서 원격 주소는 반드시 이름으로 치환된다. */
internal object RemoteErrors {

    private const val CAUSE_CHAIN_LIMIT = 5

    private val AUTHENTICATION_HINTS = listOf(
        "not authorized",
        "authentication is required",
        "authentication failed",
        "auth fail",
        "invalid credentials",
        "permission denied",
        "publickey",
        "401",
        "403",
    )

    fun translate(operation: String, remote: RemoteIdentity, error: Exception): UndineException {
        if (error is UndineException) return error
        return when {
            isAuthenticationFailure(error) -> UndineException.AuthenticationFailed(remote.label, mask(error, remote))
            else -> UndineException.GitOperationFailed(operation, mask(error, remote))
        }
    }

    private fun isAuthenticationFailure(error: Exception): Boolean =
        causeChain(error).any { link ->
            link is CredentialProviderUnavailableException ||
                link is UnsupportedCredentialItem ||
                link.message?.lowercase()?.let { message ->
                    AUTHENTICATION_HINTS.any(message::contains)
                } == true
        }

    private fun mask(error: Exception, remote: RemoteIdentity): MaskedRemoteCause {
        val description = causeChain(error).joinToString(" <- ") { link ->
            "${link.javaClass.name}: ${RemoteUrlMasking.mask(link.message, remote)}"
        }
        return MaskedRemoteCause(description).apply { stackTrace = error.stackTrace }
    }

    private fun causeChain(error: Throwable): List<Throwable> =
        generateSequence(error) { link -> link.cause.takeIf { it !== link } }
            .take(CAUSE_CHAIN_LIMIT)
            .toList()
}
