package dev.undine.infrastructure.git.signing

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.signing.SignResult
import dev.undine.domain.signing.SignatureVerdict
import dev.undine.domain.signing.SigningCommandResult
import dev.undine.domain.signing.SigningCommandRunner
import dev.undine.domain.signing.SigningFormat
import dev.undine.domain.signing.TrustLevel
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File

private const val MAIN = "main"
private const val FILE = "signed.txt"
private val IDENT = PersonIdent("Undine Signing Test", "signing-test@undine.dev")
private val SIGNING_REFUSED = SignResult.Failed.Reason.AGENT_REFUSED

class SigningGatewayImplSpec : FunSpec({

    test("Git 설정에서 GPG 서명 활성화·키·형식을 읽는다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "openpgp", signingKey = "ABCDEF", signCommits = true, signTags = true)

        withGateway(work) { gateway -> gateway.settings() } shouldBe SigningSettingsFixture.gpg
    }

    test("SSH 형식은 설정된 키를 ssh-keygen 인자 배열로 넘겨 서명한다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "ssh", signingKey = "/tmp/key with spaces")
        val runner = FakeCommandRunner(SigningCommandResult.Completed(0, "ssh-signature", ""))

        val result = withGateway(work, runner) { gateway -> gateway.sign("commit payload".toByteArray()) }

        result shouldBe SignResult.Signed("ssh-signature")
        runner.calls shouldHaveSize 1
        runner.calls.single().command shouldContainExactly
            listOf("ssh-keygen", "-Y", "sign", "-n", "git", "-f", "/tmp/key with spaces", "-")
        runner.calls.single().standardInput.toString(Charsets.UTF_8) shouldBe "commit payload"
    }

    test("GPG 형식은 설정 키와 payload를 agent 경계로 넘겨 서명한다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "openpgp", signingKey = "ABCDEF")
        val runner = FakeCommandRunner(SigningCommandResult.Completed(0, "gpg-signature", ""))

        val result = withGateway(work, runner) { gateway -> gateway.sign("commit payload".toByteArray()) }

        result shouldBe SignResult.Signed("gpg-signature")
        runner.calls.single().command shouldContainExactly
            listOf("gpg", "--armor", "--detach-sign", "--batch", "--output", "-", "--local-user", "ABCDEF")
    }

    test("SSH 키가 없으면 외부 프로그램을 실행하지 않고 실패 사유를 돌려준다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "ssh", signingKey = null)
        val runner = FakeCommandRunner(SigningCommandResult.Completed(0, "unused", ""))

        val result = withGateway(work, runner) { gateway -> gateway.sign("payload".toByteArray()) }

        result shouldBe SignResult.Failed(SignResult.Failed.Reason.NO_SIGNING_KEY, "SSH 서명 키가 설정되지 않았습니다.")
        runner.calls shouldHaveSize 0
    }

    test("agent 또는 서명 프로그램이 없으면 서명 없는 성공으로 보고하지 않는다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "openpgp", signingKey = "ABCDEF")
        val runner = FakeCommandRunner(SigningCommandResult.NotExecutable("gpg"))

        val result = withGateway(work, runner) { gateway -> gateway.sign("payload".toByteArray()) }

        result shouldBe SignResult.Failed(SignResult.Failed.Reason.PROGRAM_UNAVAILABLE, "서명 프로그램을 실행할 수 없습니다: gpg")
    }

    test("서명 프로그램이 비정상 종료하면 서명 없는 성공이 아니라 실패로 돌려준다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "openpgp", signingKey = "ABCDEF")
        val runner = FakeCommandRunner(SigningCommandResult.Completed(2, "", "gpg: signing failed: No secret key"))

        val result = withGateway(work, runner) { gateway -> gateway.sign("payload".toByteArray()) }

        result shouldBe SignResult.Failed(SIGNING_REFUSED, "서명 프로그램이 서명을 만들지 못했습니다.")
    }

    test("종료 코드가 0이어도 서명이 비어 있으면 성공으로 보고하지 않는다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "openpgp", signingKey = "ABCDEF")
        val runner = FakeCommandRunner(SigningCommandResult.Completed(0, "   \n", ""))

        val result = withGateway(work, runner) { gateway -> gateway.sign("payload".toByteArray()) }

        result shouldBe SignResult.Failed(SIGNING_REFUSED, "서명 프로그램이 서명을 만들지 못했습니다.")
    }

    test("서명 프로그램이 끝나지 못하면 그 사유를 그대로 실패로 전한다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "openpgp", signingKey = "ABCDEF")
        val runner = FakeCommandRunner(SigningCommandResult.Interrupted("서명 프로그램 응답 시간이 초과됐습니다."))

        val result = withGateway(work, runner) { gateway -> gateway.sign("payload".toByteArray()) }

        result shouldBe SignResult.Failed(SIGNING_REFUSED, "서명 프로그램 응답 시간이 초과됐습니다.")
    }

    test("이 앱이 다루지 않는 형식은 프로그램을 실행하지 않고 미지원으로 돌려준다") {
        val work = tempdir().also(::seedCommit)
        configure(work, format = "x509", signingKey = "ABCDEF")
        val runner = FakeCommandRunner(SigningCommandResult.Completed(0, "unused", ""))

        val result = withGateway(work, runner) { gateway -> gateway.sign("payload".toByteArray()) }

        result shouldBe SignResult.Failed(
            SignResult.Failed.Reason.UNSUPPORTED_FORMAT,
            "지원하지 않는 서명 형식입니다.",
        )
        runner.calls shouldHaveSize 0
    }

    test("서명되지 않은 기존 커밋은 프로그램을 호출하지 않고 서명 없음으로 반환한다") {
        val work = tempdir().also(::seedCommit)
        val runner = FakeCommandRunner(SigningCommandResult.Completed(0, "unused", ""))

        val result = withGateway(work, runner) { gateway -> gateway.verifyCommit(headOf(work)) }

        result shouldBe SignatureVerdict.NotSigned
        runner.calls shouldHaveSize 0
    }

    test("GPG 검증 상태의 유효성과 신뢰 수준을 분리해 반환한다") {
        val work = tempdir().also(::seedSignedCommit)
        val runner = FakeCommandRunner(
            SigningCommandResult.Completed(
                exitCode = 0,
                standardOutput = "[GNUPG:] GOODSIG ABCDEF Signing User <signing@undine.dev>\n[GNUPG:] TRUST_MARGINAL\n",
                standardError = "",
            ),
        )

        val result = withGateway(work, runner) { gateway -> gateway.verifyCommit(headOf(work)) }

        result shouldBe SignatureVerdict.Valid(
            format = SigningFormat.OPENPGP,
            signer = "Signing User <signing@undine.dev>",
            trust = TrustLevel.MARGINAL,
        )
        runner.calls.single().command.takeLast(3) shouldContainExactly
            listOf("verify-commit", "--raw", headOf(work).value)
    }

    test("공개키가 없는 검증 실패는 유효하지 않음과 구분한다") {
        val work = tempdir().also(::seedSignedCommit)
        val runner = FakeCommandRunner(
            SigningCommandResult.Completed(1, "[GNUPG:] NO_PUBKEY ABCDEF", ""),
        )

        val result = withGateway(work, runner) { gateway -> gateway.verifyCommit(headOf(work)) }

        result shouldBe SignatureVerdict.Undetermined(
            SignatureVerdict.Undetermined.Reason.PUBLIC_KEY_UNAVAILABLE,
            "서명자의 공개키를 찾을 수 없습니다.",
        )
    }

    test("SSH allowed_signers 매칭 검증은 FULL 신뢰로 반환한다") {
        val work = tempdir().also { directory ->
            seedSignedCommit(
                directory,
                "-----BEGIN SSH SIGNATURE-----\nfixture\n-----END SSH SIGNATURE-----\n",
            )
        }
        val runner = FakeCommandRunner(
            SigningCommandResult.Completed(0, "Good \"git\" signature for signing@undine.dev", ""),
        )

        val result = withGateway(work, runner) { gateway -> gateway.verifyCommit(headOf(work)) }

        result shouldBe SignatureVerdict.Valid(
            format = SigningFormat.SSH,
            signer = "signing@undine.dev",
            trust = TrustLevel.FULL,
        )
    }

    test("검증 프로그램이 서명을 거부하면 판단 불가가 아니라 유효하지 않음으로 구분한다") {
        val work = tempdir().also(::seedSignedCommit)
        val runner = FakeCommandRunner(
            SigningCommandResult.Completed(1, "[GNUPG:] BADSIG ABCDEF Signing User", "gpg: BAD signature"),
        )

        val result = withGateway(work, runner) { gateway -> gateway.verifyCommit(headOf(work)) }

        result shouldBe SignatureVerdict.Invalid(SigningFormat.OPENPGP, "서명 검증에 실패했습니다.")
    }

    test("서명된 annotated 태그는 태그 검증 명령으로 검증해 유효성과 신뢰 수준을 반환한다") {
        val work = tempdir().also(::seedCommit)
        seedSignedTag(work, "v1.0.0")
        val runner = FakeCommandRunner(
            SigningCommandResult.Completed(
                exitCode = 0,
                standardOutput = "[GNUPG:] GOODSIG ABCDEF Signing User <signing@undine.dev>\n[GNUPG:] TRUST_FULLY\n",
                standardError = "",
            ),
        )

        val result = withGateway(work, runner) { gateway -> gateway.verifyTag(RefName("v1.0.0")) }

        result shouldBe SignatureVerdict.Valid(
            format = SigningFormat.OPENPGP,
            signer = "Signing User <signing@undine.dev>",
            trust = TrustLevel.FULL,
        )
        runner.calls.single().command.takeLast(3) shouldContainExactly
            listOf("verify-tag", "--raw", "refs/tags/v1.0.0")
    }

    test("경량 태그는 서명할 객체가 없으므로 서명 없음으로 반환한다") {
        val work = tempdir().also(::seedCommit)
        Git.open(work).use { git -> git.tag().setName("lightweight").setAnnotated(false).call() }
        val runner = FakeCommandRunner(SigningCommandResult.Completed(0, "unused", ""))

        val result = withGateway(work, runner) { gateway -> gateway.verifyTag(RefName("lightweight")) }

        result shouldBe SignatureVerdict.NotSigned
        runner.calls shouldHaveSize 0
    }
})

private object SigningSettingsFixture {
    val gpg = dev.undine.domain.signing.SigningSettings(
        signCommits = true,
        signTags = true,
        format = SigningFormat.OPENPGP,
        signingKey = "ABCDEF",
    )
}

private fun seedCommit(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, FILE).writeText("initial\n")
        git.add().addFilepattern(FILE).call()
        git.commit().setMessage("initial").setAuthor(IDENT).setCommitter(IDENT).call()
    }
}

private fun seedSignedCommit(
    work: File,
    signature: String = "-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n",
) {
    seedCommit(work)
    // 실제 Git 객체를 만들되 검증 프로그램만 가짜로 바꾼다. 외부 키·agent는 필요 없다.
    Git.open(work).use { git ->
        val parent = git.repository.resolve("HEAD")
        val tree = org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk -> walk.parseCommit(parent).tree }
        val builder = org.eclipse.jgit.lib.CommitBuilder().apply {
            setTreeId(tree)
            setParentId(parent)
            setAuthor(IDENT)
            setCommitter(IDENT)
            setMessage("signed fixture")
            setGpgSignature(org.eclipse.jgit.lib.GpgSignature(signature.toByteArray()))
        }
        git.repository.newObjectInserter().use { inserter ->
            val id = inserter.insert(builder)
            inserter.flush()
            git.repository.updateRef("refs/heads/$MAIN").apply {
                setNewObjectId(id)
                update()
            }
        }
    }
}

/** 서명된 annotated 태그. 커밋과 마찬가지로 실제 Git 객체를 만들고 검증 프로그램만 가짜로 바꾼다. */
private fun seedSignedTag(
    work: File,
    name: String,
    signature: String = "-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n",
) {
    Git.open(work).use { git ->
        val target = requireNotNull(git.repository.resolve("HEAD"))
        val builder = org.eclipse.jgit.lib.TagBuilder().apply {
            setTag(name)
            setObjectId(target, org.eclipse.jgit.lib.Constants.OBJ_COMMIT)
            setTagger(IDENT)
            // 서명된 태그의 메시지는 LF 로 끝나야 한다 (git 객체 형식).
            setMessage("signed tag fixture\n")
            setGpgSignature(org.eclipse.jgit.lib.GpgSignature(signature.toByteArray()))
        }
        git.repository.newObjectInserter().use { inserter ->
            val id = inserter.insert(builder)
            inserter.flush()
            git.repository.updateRef("refs/tags/$name").apply {
                setNewObjectId(id)
                update()
            }
        }
    }
}

private fun configure(
    work: File,
    format: String,
    signingKey: String?,
    signCommits: Boolean = false,
    signTags: Boolean = false,
) {
    Git.open(work).use { git ->
        git.repository.config.apply {
            setString("gpg", null, "format", format)
            setBoolean("commit", null, "gpgsign", signCommits)
            setBoolean("tag", null, "gpgSign", signTags)
            if (signingKey == null) {
                unset("user", null, "signingkey")
            } else {
                setString("user", null, "signingkey", signingKey)
            }
            save()
        }
    }
}

private suspend fun <T> withGateway(
    work: File,
    runner: SigningCommandRunner = FakeCommandRunner(),
    block: suspend (SigningGatewayImpl) -> T,
): T {
    val access = GitAccess()
    access.open(RepositoryPath(work.path)) { }
    return try {
        block(SigningGatewayImpl(access, runner))
    } finally {
        access.close()
    }
}

private fun headOf(work: File): CommitId =
    Git.open(work).use { git -> CommitId.of(requireNotNull(git.repository.resolve("HEAD")).name) }

private class FakeCommandRunner(vararg results: SigningCommandResult) : SigningCommandRunner {
    private val queued = ArrayDeque(results.asList())
    val calls = mutableListOf<Call>()

    override suspend fun run(command: List<String>, standardInput: ByteArray): SigningCommandResult {
        calls += Call(command, standardInput)
        return queued.removeFirstOrNull() ?: error("예상하지 않은 프로세스 호출입니다: $command")
    }

    data class Call(val command: List<String>, val standardInput: ByteArray)
}
