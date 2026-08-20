package dev.undine.infrastructure.git.remote

import dev.undine.domain.CommitId
import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway
import dev.undine.domain.RemoteRef
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File
import java.text.MessageFormat
import kotlin.coroutines.CoroutineContext

private val ACCEPTED_STATUSES = setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE)

/** clone 이 만드는 원격의 이름. clone 실패 예외에는 URL 대신 이 이름만 싣는다. */
private const val DEFAULT_REMOTE = "origin"

/** force push 직전에 원격의 기존 tip 을 담아 두는 로컬 백업 ref 의 접두사. */
internal const val FORCE_PUSH_BACKUP_PREFIX = "refs/undine/force-push-backup"

/**
 * JGit 으로 구현한 원격 연동.
 *
 * 공유 [Repository] 핸들은 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * fetch·pull·push 가 동시에 들어오면 손상된다. 직렬화와 `Dispatchers.IO` 전환은 [GitAccess] 가
 * 책임지므로 이 클래스는 락도 디스패처도 다시 걸지 않는다. `clone` 은 아직 저장소가 없는 경로를
 * 대상으로 하므로 [GitAccess] 를 쓰지 않고 **자기가 만든 핸들만 자기가 닫는다.**
 *
 * 자격증명은 앱이 보관하지 않는다 — HTTPS 는 [GitCredentialHelperProvider] 가 git credential helper 에,
 * SSH 는 [UndineSshSessionFactory] 가 `~/.ssh/config` 와 ssh-agent 에 위임한다. 위임할 곳이 없으면
 * 익명 접근으로 떨어지지 않고 [UndineException.AuthenticationFailed] 로 끝난다.
 *
 * `force = true` 인 push 는 원격 이력을 덮어써 되돌릴 수 없다. **호출 전 사용자 확인이 필요하며**
 * (확인 절차는 툴바 티켓(UND-18) 소유), 그 앞단의 최후 방어선으로 이 클래스가 덮어쓰기 직전
 * 원격의 기존 tip 을 `refs/undine/force-push-backup/...` 에 남긴다 — 백업에 실패하면 push 하지 않는다.
 * 백업한 tip 은 그대로 **lease** 로도 쓴다(`--force-with-lease`) — 백업 이후 다른 클라이언트가 원격을
 * 갱신했다면 서버가 거절한다. 백업만으로는 백업 이후의 갱신을 복구할 수 없기 때문이다.
 */
class RemoteGatewayImpl(
    private val gitAccess: GitAccess,
    private val credentialsProvider: CredentialsProvider = GitCredentialHelperProvider(),
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteGateway {

    override suspend fun clone(url: String, into: RepositoryPath, onProgress: (Progress) -> Unit) {
        val remote = RemoteIdentity(label = DEFAULT_REMOTE, url = url)
        runDetached(operation = "remote.clone", remote = remote, onProgress = onProgress) { monitor ->
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(File(into.value))
                .setProgressMonitor(monitor)
                .setCredentialsProvider(credentialsProvider)
                .call()
                .use { /* clone 이 연 핸들은 이 함수가 소유한다 — 열어 둔 채 넘기지 않는다 */ }
        }
    }

    /**
     * 원격이 알려 온 참조 목록을 돌려준다. 원격 추적 ref 는 갱신되지만 로컬 브랜치는 건드리지 않는다.
     * annotated 태그 참조는 커밋이 아니라 태그 객체를 가리킬 수 있다.
     */
    override suspend fun fetch(remote: String, onProgress: (Progress) -> Unit): List<RemoteRef> {
        var identity = RemoteIdentity(label = remote, url = null)
        return runOnRepository("remote.fetch", { identity }, onProgress) { repository, monitor ->
            requireKnownRemote(repository, remote)
            identity = repository.identityOf(remote)
            Git(repository).use { git ->
                git.fetch()
                    .setRemote(remote)
                    .setProgressMonitor(monitor)
                    .setCredentialsProvider(credentialsProvider)
                    .call()
            }.advertisedRefs.mapNotNull { advertised -> advertised.toRemoteRef(remote) }
        }
    }

    override suspend fun pull(remote: String, onProgress: (Progress) -> Unit) {
        var identity = RemoteIdentity(label = remote, url = null)
        runOnRepository("remote.pull", { identity }, onProgress) { repository, monitor ->
            requireKnownRemote(repository, remote)
            identity = repository.identityOf(remote)
            val result = Git(repository).use { git ->
                git.pull()
                    .setRemote(remote)
                    .setProgressMonitor(monitor)
                    .setCredentialsProvider(credentialsProvider)
                    .call()
            }
            ensureMerged(result)
        }
    }

    /**
     * 현재 브랜치의 업스트림 원격으로 [ref] 를 보낸다 — 인자 없는 `git push` 와 같은 대상 결정이다.
     * 원격이 거절한 것은 실패가 아니라 결과이므로 [PushResult.Rejected] 로 돌려준다.
     */
    override suspend fun push(ref: RefName, force: Boolean, onProgress: (Progress) -> Unit): PushResult {
        var identity = RemoteIdentity(label = DEFAULT_REMOTE, url = null)
        return runOnRepository("remote.push", { identity }, onProgress) { repository, monitor ->
            val remote = upstreamRemoteOf(repository)
            identity = repository.identityOf(remote)
            Git(repository).use { git ->
                val destination = destinationOf(ref)
                val lease = if (force) backupRemoteTip(git, remote, destination, monitor) else null
                git.push()
                    .setRemote(remote)
                    .setRefSpecs(RefSpec(ref.value))
                    .setForce(force)
                    .apply { lease?.let { setRefLeaseSpecs(RefLeaseSpec(destination, it)) } }
                    .setProgressMonitor(monitor)
                    .setCredentialsProvider(credentialsProvider)
                    .call()
            }.flatMap { pushed -> pushed.remoteUpdates }.toPushResult(ref)
        }
    }

    /**
     * force push 로 지워질 원격 tip 을 로컬 백업 ref 로 먼저 가져온다 — 커밋 객체까지 함께 받아 두므로
     * 덮어쓴 뒤에도 `git reset --hard <백업 ref>` 로 되돌릴 수 있다.
     *
     * 네트워크 왕복은 **fetch 한 번뿐**이다. 존재 확인을 `lsRemote` 로 따로 하면 그 왕복에는
     * [ProgressMonitor] 를 물릴 수 없어 취소가 응답 대기까지 전파되지 않는다 — fetch 의 advertise 결과가
     * 같은 정보를 주므로 왕복을 하나로 줄이고 전 구간을 취소 가능하게 둔다.
     *
     * 원격에 아직 그 참조가 없으면(신규 브랜치) 덮어쓸 이력이 없으므로 백업을 생략하고 `null` 을 준다.
     * 백업이 확인되지 않으면 [UndineException.GitOperationFailed] 로 멈춘다 — **복구 경로 없이 덮어쓰지 않는다.**
     *
     * @return push 의 lease 로 쓸 백업 ref 이름. 신규 브랜치면 `null`.
     */
    private fun backupRemoteTip(git: Git, remote: String, destination: String, monitor: ProgressMonitor): String? {
        monitor.ensureNotCancelled("원격 백업 전에 취소되었습니다")
        val backupRef = "$FORCE_PUSH_BACKUP_PREFIX/$remote/${destination.removePrefix(Constants.R_HEADS)}-${now()}"
        val remoteTip = fetchIntoBackup(git, remote, destination, backupRef, monitor) ?: return null
        if (git.repository.resolve(backupRef) != remoteTip) {
            throw UndineException.GitOperationFailed("remote.push.backup")
        }
        return backupRef
    }

    /**
     * [destination] 을 [backupRef] 로 가져오고 원격이 광고한 tip 을 돌려준다.
     * 원격에 그 참조가 없으면 `null` — 덮어쓸 이력이 없는 신규 브랜치다.
     */
    private fun fetchIntoBackup(
        git: Git,
        remote: String,
        destination: String,
        backupRef: String,
        monitor: ProgressMonitor,
    ): ObjectId? =
        try {
            git.fetch()
                .setRemote(remote)
                .setRefSpecs(RefSpec("+$destination:$backupRef"))
                .setProgressMonitor(monitor)
                .setCredentialsProvider(credentialsProvider)
                .call()
                .getAdvertisedRef(destination)
                ?.objectId
        } catch (absent: TransportException) {
            if (!absent.isMissingSource(destination)) throw absent
            null
        }

    /** clone 은 저장소가 없는 경로를 대상으로 하므로 [GitAccess] 를 거치지 않고 직접 IO 로 넘긴다. */
    private suspend fun <T> runDetached(
        operation: String,
        remote: RemoteIdentity,
        onProgress: (Progress) -> Unit,
        block: (ProgressMonitor) -> T,
    ): T = guarded(operation, { remote }, onProgress) { monitor ->
        withContext(Dispatchers.IO) { block(monitor) }
    }

    /**
     * 저장소 조회부터 Git 명령까지 **전부** [GitAccess] 의 직렬화된 IO 경계 안에서 실행한다.
     * 원격 이름·업스트림 조회를 경계 밖에 두면 호출부(UI) 코루틴에서 저장소를 읽게 된다.
     */
    private suspend fun <T> runOnRepository(
        operation: String,
        remote: () -> RemoteIdentity,
        onProgress: (Progress) -> Unit,
        block: (Repository, ProgressMonitor) -> T,
    ): T = guarded(operation, remote, onProgress) { monitor ->
        gitAccess.withRepository { repository -> block(repository, monitor) }
    }

    /**
     * 취소·예외 번역을 한곳에서 처리한다. 취소는 [CancellationException] 으로 전파하며
     * 도메인 예외로 바꾸지 않는다.
     *
     * JGit 은 검사 예외([org.eclipse.jgit.api.errors.GitAPIException])와 런타임 예외
     * ([org.eclipse.jgit.api.errors.JGitInternalException] 등)를 섞어 던지고, 그 메시지에는 토큰이 든
     * 원격 URL 이 실려 있다. 종류를 열거하다 하나라도 놓치면 그대로 화면까지 올라가므로
     * 여기서는 의도적으로 넓게 잡아 전부 마스킹한 도메인 예외로 번역한다.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guarded(
        operation: String,
        remote: () -> RemoteIdentity,
        onProgress: (Progress) -> Unit,
        run: suspend (ProgressMonitor) -> T,
    ): T {
        UndineSshSessionFactory.installOnce()
        val caller: CoroutineContext = currentCoroutineContext()
        val monitor = RemoteProgressMonitor(cancelled = { !caller.isActive }, onProgress = onProgress)
        val result = try {
            run(monitor)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            caller.ensureActive()
            throw RemoteErrors.translate(operation, remote(), failure)
        }
        caller.ensureActive()
        return result
    }
}

/**
 * 원격이 그 참조를 광고하지 않아 refspec 을 채울 수 없다는 실패인지 본다.
 * 메시지 문자열을 직접 적지 않고 JGit 의 원문 템플릿과 대조한다 — 로케일이 바뀌어도 성립한다.
 */
private fun TransportException.isMissingSource(source: String): Boolean =
    message == MessageFormat.format(JGitText.get().remoteDoesNotHaveSpec, source)

/** 명령 시작 전에 취소를 확인한다 — 시작한 왕복은 [ProgressMonitor] 가 끊는다. */
private fun ProgressMonitor.ensureNotCancelled(detail: String) {
    if (isCancelled) throw CancellationException(detail)
}

private fun requireKnownRemote(repository: Repository, remote: String) {
    if (remote !in repository.remoteNames) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.REMOTE, remote)
    }
}

/** 원격 URL 은 마스킹에만 쓴다 — 예외에는 이름만 실린다. */
private fun Repository.identityOf(remote: String): RemoteIdentity =
    RemoteIdentity(label = remote, url = config.getString("remote", remote, "url"))

private fun upstreamRemoteOf(repository: Repository): String {
    val branch = repository.branch ?: throw UndineException.StateViolation("현재 브랜치를 확인할 수 없습니다")
    return BranchConfig(repository.config, branch).remote
        ?: throw UndineException.StateViolation("업스트림이 설정되지 않았습니다")
}

/** 짧은 이름(`main`)으로 들어와도 원격에서는 완전한 참조 이름으로 대응된다. */
private fun destinationOf(ref: RefName): String =
    if (ref.value.startsWith(Constants.R_REFS)) ref.value else Constants.R_HEADS + ref.value

private fun ensureMerged(result: PullResult) {
    if (result.isSuccessful) return
    val conflicts = result.mergeResult?.conflicts?.keys?.toList().orEmpty()
    if (conflicts.isNotEmpty()) throw UndineException.Conflict(conflicts)
    throw UndineException.GitOperationFailed("remote.pull")
}

private fun List<RemoteRefUpdate>.toPushResult(ref: RefName): PushResult {
    if (isEmpty()) throw UndineException.NotFound(UndineException.NotFound.Kind.REF, ref.value)
    val rejected = firstOrNull { update -> update.status !in ACCEPTED_STATUSES } ?: return PushResult.Accepted
    return PushResult.Rejected(
        if (rejected.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
            PushResult.RejectReason.NON_FAST_FORWARD
        } else {
            PushResult.RejectReason.REMOTE_REJECTED
        },
    )
}

private fun Ref.toRemoteRef(remote: String): RemoteRef? =
    objectId?.let { target -> RemoteRef(remote = remote, name = RefName(name), target = CommitId.of(target.name)) }
