package dev.undine.infrastructure.git.remote

import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `git credential fill` 헬퍼를 실행해 응답 본문을 돌려준다. 헬퍼를 실행할 수 없으면 `null`.
 *
 * 응답에는 비밀번호가 들어 있으므로 **어디에도 기록하지 않는다.**
 */
internal fun interface CredentialHelperRunner {

    fun fill(request: String): String?
}

/**
 * HTTPS 원격의 자격증명을 **기존 git credential helper 에 위임**하는 제공자.
 *
 * 앱은 자격증명을 저장하지도 묻지도 않는다 ([isInteractive] 는 항상 `false`).
 * JGit 에는 `git credential` 헬퍼 프로토콜 구현이 없으므로 헬퍼를 서브프로세스로 호출한다.
 *
 * - 헬퍼를 실행할 수 없거나 사용자명·비밀번호를 얻지 못하면 [CredentialProviderUnavailableException] 으로
 *   **안전하게 실패**한다. 빈 자격증명으로 익명 접근을 시도하지 않는다.
 * - SSH 원격은 헬퍼 대상이 아니다. `~/.ssh/config` 와 ssh-agent 가 인증을 마쳐야 하며
 *   ([UndineSshSessionFactory]), 그럼에도 여기까지 물어 오면 위임할 곳이 없다는 뜻이므로 실패시킨다.
 */
class GitCredentialHelperProvider internal constructor(
    private val runner: CredentialHelperRunner,
) : CredentialsProvider() {

    constructor() : this(ProcessCredentialHelperRunner())

    override fun isInteractive(): Boolean = false

    override fun supports(vararg items: CredentialItem): Boolean =
        items.all { item ->
            item is CredentialItem.Username ||
                item is CredentialItem.Password ||
                item is CredentialItem.InformationalMessage
        }

    override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
        if (uri.scheme !in HELPER_SCHEMES) {
            throw CredentialProviderUnavailableException(
                "'${uri.scheme}' 원격은 credential helper 대상이 아닙니다. $SETUP_GUIDE",
            )
        }
        val answer = runner.fill(requestOf(uri))
            ?: throw CredentialProviderUnavailableException("git credential helper 를 실행할 수 없습니다. $SETUP_GUIDE")
        val fields = parse(answer)
        val username = fields[FIELD_USERNAME]
        val password = fields[FIELD_PASSWORD]
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            throw CredentialProviderUnavailableException("credential helper 가 자격증명을 제공하지 않았습니다. $SETUP_GUIDE")
        }
        items.forEach { item -> fill(uri, item, username, password) }
        return true
    }

    private fun fill(uri: URIish, item: CredentialItem, username: String, password: String) {
        when (item) {
            is CredentialItem.Username -> item.value = username
            is CredentialItem.Password -> item.setValueNoCopy(password.toCharArray())
            is CredentialItem.InformationalMessage -> Unit
            else -> throw UnsupportedCredentialItem(uri, item.promptText.orEmpty())
        }
    }

    private fun requestOf(uri: URIish): String = buildString {
        append("protocol=${uri.scheme}\n")
        append("host=${hostOf(uri)}\n")
        uri.path?.trimStart('/')?.takeIf { it.isNotEmpty() }?.let { append("path=$it\n") }
        uri.user?.takeIf { it.isNotEmpty() }?.let { append("username=$it\n") }
        append("\n")
    }

    private fun hostOf(uri: URIish): String =
        if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host.orEmpty()

    private fun parse(answer: String): Map<String, String> =
        answer.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()

    companion object {
        /** 화면이 사용자에게 보여줄 설정 안내. 자격증명 값은 담지 않는다. */
        const val SETUP_GUIDE: String =
            "HTTPS 는 `git config --global credential.helper` 로 자격증명 헬퍼를 설정하고, " +
                "SSH 는 `ssh-add` 로 ssh-agent 에 키를 등록한 뒤 ~/.ssh/config 를 확인하세요."

        private val HELPER_SCHEMES = setOf("http", "https")
        private const val FIELD_USERNAME = "username"
        private const val FIELD_PASSWORD = "password"
    }
}

/** `git credential fill` 을 서브프로세스로 실행한다. */
internal class ProcessCredentialHelperRunner(
    private val command: List<String> = DEFAULT_COMMAND,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : CredentialHelperRunner {

    override fun fill(request: String): String? =
        try {
            runHelper(request)
        } catch (failure: IOException) {
            // 헬퍼 미설치·실행 불가. 조용히 넘기지 않고 곧바로 '제공자 없음' 실패로 끝낸다.
            throw CredentialProviderUnavailableException(
                "git credential helper 를 실행할 수 없습니다. ${GitCredentialHelperProvider.SETUP_GUIDE}",
                failure,
            )
        }

    /**
     * 응답을 읽기 **전에** 종료를 기다린다 — 읽기부터 하면 응답하지 않는 헬퍼에서 그대로 막혀
     * 시간 초과가 영영 걸리지 않는다. 응답은 자격증명 한 줄 수준이라 파이프 버퍼로 충분하다.
     */
    private fun runHelper(request: String): String? {
        val process = ProcessBuilder(command).start()
        process.outputStream.bufferedWriter().use { writer -> writer.write(request) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return process.inputStream.bufferedReader()
            .use { reader -> reader.readText() }
            .takeIf { process.exitValue() == 0 }
    }

    private companion object {
        val DEFAULT_COMMAND = listOf("git", "credential", "fill")
        const val DEFAULT_TIMEOUT_SECONDS = 10L
    }
}
