package dev.undine.infrastructure.git.signing

import dev.undine.domain.signing.SignatureVerdict
import dev.undine.domain.signing.SigningCommandResult
import dev.undine.domain.signing.SigningCommandRunner
import dev.undine.domain.signing.SigningFormat
import dev.undine.domain.signing.TrustLevel

internal const val GPG_FORMAT_OPENPGP = "openpgp"
internal const val GPG_FORMAT_SSH = "ssh"
private const val GPG_FORMAT_X509 = "x509"
private const val GPG_SIGNATURE_HEADER = "-----BEGIN PGP SIGNATURE-----"
private const val SSH_SIGNATURE_HEADER = "-----BEGIN SSH SIGNATURE-----"
private const val UNKNOWN_SIGNER = "알 수 없는 서명자"

/** 검증 프로그램에 넘길 명령과, 그 명령이 다루는 서명 형식. */
internal data class VerificationInput(
    val format: SigningFormat,
    val command: List<String>,
)

internal suspend fun VerificationInput.verify(runner: SigningCommandRunner): SignatureVerdict =
    runner.run(command, ByteArray(0)).toSignatureVerdict(format)

private fun SigningCommandResult.toSignatureVerdict(format: SigningFormat): SignatureVerdict = when (this) {
    is SigningCommandResult.NotExecutable -> SignatureVerdict.Undetermined(
        SignatureVerdict.Undetermined.Reason.PROGRAM_UNAVAILABLE,
        "검증 프로그램을 실행할 수 없습니다: $program",
    )

    is SigningCommandResult.Interrupted -> SignatureVerdict.Undetermined(
        SignatureVerdict.Undetermined.Reason.VERIFIER_FAILED,
        detail,
    )

    is SigningCommandResult.Completed -> parseVerification(format, exitCode, "$standardOutput\n$standardError")
}

/**
 * 검증 프로그램의 출력을 결과로 옮긴다.
 *
 * **판단하지 못한 것을 [SignatureVerdict.Invalid] 로 뭉개지 않는다** — 공개키가 없거나 프로그램이
 * 없는 것은 서명이 틀렸다는 뜻이 아니다. 정상 서명을 위조로 알리면 사용자가 정반대로 판단한다.
 */
private fun parseVerification(format: SigningFormat, exitCode: Int, output: String): SignatureVerdict {
    if (exitCode == 0) {
        return SignatureVerdict.Valid(format, output.signerFor(format), output.trustFor(format))
    }
    return when {
        output.contains("NO_PUBKEY") -> SignatureVerdict.Undetermined(
            SignatureVerdict.Undetermined.Reason.PUBLIC_KEY_UNAVAILABLE,
            "서명자의 공개키를 찾을 수 없습니다.",
        )

        output.contains("not found", ignoreCase = true) || output.contains("No such file", ignoreCase = true) ->
            SignatureVerdict.Undetermined(
                SignatureVerdict.Undetermined.Reason.PROGRAM_UNAVAILABLE,
                "검증 프로그램을 실행할 수 없습니다.",
            )

        else -> SignatureVerdict.Invalid(format, "서명 검증에 실패했습니다.")
    }
}

private fun String.signerFor(format: SigningFormat): String = when (format) {
    SigningFormat.OPENPGP -> GOODSIG_PATTERN.find(this)?.groupValues?.get(1) ?: UNKNOWN_SIGNER
    SigningFormat.SSH -> SSH_SIGNER_PATTERN.find(this)?.groupValues?.get(1) ?: UNKNOWN_SIGNER
    SigningFormat.X509 -> UNKNOWN_SIGNER
}

private fun String.trustFor(format: SigningFormat): TrustLevel = when (format) {
    SigningFormat.SSH -> TrustLevel.FULL
    SigningFormat.X509 -> TrustLevel.UNKNOWN
    SigningFormat.OPENPGP -> when {
        contains("TRUST_ULTIMATE") -> TrustLevel.ULTIMATE
        contains("TRUST_FULLY") -> TrustLevel.FULL
        contains("TRUST_MARGINAL") -> TrustLevel.MARGINAL
        contains("TRUST_NEVER") -> TrustLevel.NEVER
        else -> TrustLevel.UNKNOWN
    }
}

/** 서명 블록의 armor 머리글로 형식을 가른다 — 어느 쪽도 아니면 이 앱이 다루지 않는 형식이다. */
internal fun ByteArray.toSigningFormat(): SigningFormat {
    val header = toString(Charsets.UTF_8)
    return when {
        header.startsWith(SSH_SIGNATURE_HEADER) -> SigningFormat.SSH
        header.startsWith(GPG_SIGNATURE_HEADER) -> SigningFormat.OPENPGP
        else -> SigningFormat.X509
    }
}

internal fun SigningFormat.toGitConfigValue(): String = when (this) {
    SigningFormat.OPENPGP -> GPG_FORMAT_OPENPGP
    SigningFormat.SSH -> GPG_FORMAT_SSH
    SigningFormat.X509 -> GPG_FORMAT_X509
}

private val GOODSIG_PATTERN = Regex("""(?m)^\[GNUPG:] GOODSIG \S+ (.+)$""")
private val SSH_SIGNER_PATTERN = Regex("""Good "git" signature for (.+)""")
