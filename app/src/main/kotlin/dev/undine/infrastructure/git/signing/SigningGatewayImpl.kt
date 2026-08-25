package dev.undine.infrastructure.git.signing

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.signing.SignResult
import dev.undine.domain.signing.SignatureVerdict
import dev.undine.domain.signing.SigningCommandResult
import dev.undine.domain.signing.SigningCommandRunner
import dev.undine.domain.signing.SigningFormat
import dev.undine.domain.signing.SigningGateway
import dev.undine.domain.signing.SigningSettings
import dev.undine.infrastructure.git.repository.GitAccess
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException

private const val GPG_PROGRAM = "gpg"
private const val SSH_KEYGEN_PROGRAM = "ssh-keygen"
private const val GIT_PROGRAM = "git"
private const val TAG_REF_PREFIX = "refs/tags/"
private const val NO_SIGNING_KEY_DETAIL = "SSH 서명 키가 설정되지 않았습니다."
private const val UNSUPPORTED_FORMAT_DETAIL = "지원하지 않는 서명 형식입니다."

/**
 * [SigningGateway] 의 Git 설정·agent 기반 구현.
 *
 * JGit 접근은 모두 [GitAccess.withRepository] 내부에서 끝낸다. 프로세스 호출은 domain 계약으로
 * 분리했으므로 실제 gpg/ssh-agent가 없는 CI에서도 판단 로직을 검증할 수 있다.
 */
class SigningGatewayImpl(
    private val gitAccess: GitAccess,
    private val commandRunner: SigningCommandRunner = ProcessSigningCommandRunner(),
) : SigningGateway {

    override suspend fun settings(): SigningSettings = gitAccess.withRepository { repository ->
        SigningSettings(
            signCommits = repository.config.getBoolean("commit", null, "gpgsign", false),
            signTags = repository.config.getBoolean("tag", null, "gpgsign", false),
            format = repository.config.signingFormat(),
            signingKey = repository.config.getString("user", null, "signingkey")?.takeIf(String::isNotBlank),
        )
    }

    override suspend fun sign(payload: ByteArray): SignResult {
        val settings = settings()
        val command = signingCommand(settings) ?: return SignResult.Failed(
            reason = if (settings.format == SigningFormat.SSH) {
                SignResult.Failed.Reason.NO_SIGNING_KEY
            } else {
                SignResult.Failed.Reason.UNSUPPORTED_FORMAT
            },
            detail = if (settings.format == SigningFormat.SSH) NO_SIGNING_KEY_DETAIL else UNSUPPORTED_FORMAT_DETAIL,
        )
        return commandRunner.run(command, payload).toSignResult()
    }

    override suspend fun verifyCommit(commit: CommitId): SignatureVerdict {
        val input = gitOperation("signing.verifyCommit") { repository -> repository.commitVerificationInput(commit) }
        return input?.verify(commandRunner) ?: SignatureVerdict.NotSigned
    }

    override suspend fun verifyTag(tag: RefName): SignatureVerdict {
        val input = gitOperation("signing.verifyTag") { repository -> repository.tagVerificationInput(tag) }
        return input?.verify(commandRunner) ?: SignatureVerdict.NotSigned
    }

    private suspend fun <T> gitOperation(operation: String, block: (Repository) -> T): T =
        try {
            gitAccess.withRepository(block)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (translated: UndineException) {
            throw translated
        } catch (failure: IOException) {
            throw UndineException.GitOperationFailed(operation, failure)
        }
}

private fun signingCommand(settings: SigningSettings): List<String>? = when (settings.format) {
    SigningFormat.OPENPGP -> buildList {
        add(GPG_PROGRAM)
        addAll(listOf("--armor", "--detach-sign", "--batch", "--output", "-"))
        settings.signingKey?.let { key -> addAll(listOf("--local-user", key)) }
    }

    SigningFormat.SSH -> settings.signingKey?.let { key ->
        listOf(SSH_KEYGEN_PROGRAM, "-Y", "sign", "-n", "git", "-f", key, "-")
    }

    SigningFormat.X509 -> null
}

/**
 * 서명 프로그램의 결과를 [SignResult] 로 옮긴다.
 *
 * **서명이 비어 있으면 성공으로 보지 않는다** — 호출부가 이걸 성공으로 받으면 서명 없는 커밋을
 * 만들어 놓고 사용자에게는 서명됐다고 말하게 된다.
 */
private fun SigningCommandResult.toSignResult(): SignResult = when (this) {
    is SigningCommandResult.Completed -> {
        if (exitCode == 0 && standardOutput.isNotBlank()) {
            SignResult.Signed(standardOutput)
        } else {
            SignResult.Failed(SignResult.Failed.Reason.AGENT_REFUSED, "서명 프로그램이 서명을 만들지 못했습니다.")
        }
    }

    is SigningCommandResult.NotExecutable ->
        SignResult.Failed(SignResult.Failed.Reason.PROGRAM_UNAVAILABLE, "서명 프로그램을 실행할 수 없습니다: $program")

    is SigningCommandResult.Interrupted ->
        SignResult.Failed(SignResult.Failed.Reason.AGENT_REFUSED, detail)
}

private fun Repository.commitVerificationInput(commit: CommitId): VerificationInput? {
    val objectId = resolve(commit.value)
        ?: throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
    val signature = RevWalk(this).use { walk -> walk.parseCommit(objectId).rawGpgSignature } ?: return null
    return VerificationInput(signature.toSigningFormat(), verificationCommand("verify-commit", commit.value, signature))
}

private fun Repository.tagVerificationInput(tag: RefName): VerificationInput? {
    val fullName = tag.value.removePrefix(TAG_REF_PREFIX).let { "$TAG_REF_PREFIX$it" }
    val ref = exactRef(fullName) ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, tag.value)
    val parsed = RevWalk(this).use { walk -> walk.parseAny(ref.objectId) }
    // 경량 태그는 서명을 실을 태그 객체 자체가 없다.
    val signature = (parsed as? RevTag)?.rawGpgSignature ?: return null
    return VerificationInput(signature.toSigningFormat(), verificationCommand("verify-tag", fullName, signature))
}

private fun Repository.verificationCommand(operation: String, target: String, signature: ByteArray): List<String> =
    listOf(
        GIT_PROGRAM,
        "-C",
        workTree.absolutePath,
        "-c",
        "gpg.format=${signature.toSigningFormat().toGitConfigValue()}",
        operation,
        "--raw",
        target,
    )

private fun StoredConfig.signingFormat(): SigningFormat = when (getString("gpg", null, "format")?.lowercase()) {
    null, GPG_FORMAT_OPENPGP -> SigningFormat.OPENPGP
    GPG_FORMAT_SSH -> SigningFormat.SSH
    else -> SigningFormat.X509
}
