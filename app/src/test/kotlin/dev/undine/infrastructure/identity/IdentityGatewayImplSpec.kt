package dev.undine.infrastructure.identity

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.IdentityProfile
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.UndineException
import dev.undine.domain.identity.IdentityService
import dev.undine.domain.identity.IdentityWarning
import dev.undine.infrastructure.git.history.HistoryGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.settings.SettingsGatewayImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch

private const val INITIAL_BRANCH = "main"
private const val CODE = "code.txt"
private const val WORK_PROFILE = "회사"
private const val WORK_EMAIL = "me@work.example"
private const val PERSONAL_EMAIL = "me@personal.example"
private const val SIGNING_KEY = "ABCD1234"

private const val USER = "user"
private const val UNDINE = "undine"
private const val IDENTITY_PROFILE = "identityProfile"

/** 구현이 실패에 붙이는 연산 이름 — 화면이 어떤 동작이 실패했는지 말하려면 서로 달라야 한다. */
private const val OPERATION_APPLY = "identity.applyProfile"
private const val OPERATION_CLEAR = "identity.clearLocalIdentity"

/** 최근 50건 경계 검증용 — 50건 안쪽과 바깥쪽을 한 번씩 만든다. */
private const val SCAN_LIMIT = 50

/** 동시 갱신 검증용 — 하나라도 사라지면 직렬화가 깨진 것이다. */
private const val CONCURRENT_PROFILE_COUNT = 8

private val WORK = IdentityProfile(
    name = WORK_PROFILE,
    email = WORK_EMAIL,
    signingKeyId = SIGNING_KEY,
    defaultAuthentication = AuthenticationMethod.SSH,
    expectedHost = null,
)

/**
 * identity Gateway — **실제 임시 저장소**로 검증한다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1).
 *
 * 이 티켓의 핵심 약속은 "전역 설정을 건드리지 않는다" 라서, 로컬 config 파일을 **base 없이** 직접
 * 열어 읽고 전역 파일은 실행 전후 내용을 비교한다. 전역이 상속돼 보이는 `Repository.getConfig()`
 * 로 읽으면 로컬에 쓰였는지 아닌지를 구분하지 못한다.
 */
class IdentityGatewayImplSpec : FunSpec({

    test("프로필을 적용하면 저장소 로컬 설정에만 반영되고 전역 설정은 변경되지 않는다") {
        val work = tempdir().also(::seedRepository)
        val globalBefore = globalConfigSnapshot()

        withIdentityGateway(work, settingsFile()) { gateway -> gateway.applyProfile(WORK) }

        val local = localConfig(work)
        local.getString(USER, null, "name") shouldBe WORK_PROFILE
        local.getString(USER, null, "email") shouldBe WORK_EMAIL
        local.getString(USER, null, "signingkey") shouldBe SIGNING_KEY
        local.getString(UNDINE, null, IDENTITY_PROFILE) shouldBe WORK_PROFILE
        globalConfigSnapshot() shouldBe globalBefore
    }

    test("서명 키가 없는 프로필을 적용하면 앞 프로필의 로컬 서명 키를 지운다") {
        val work = tempdir().also(::seedRepository)

        withIdentityGateway(work, settingsFile()) { gateway ->
            gateway.applyProfile(WORK)
            gateway.applyProfile(WORK.copy(name = "개인", email = PERSONAL_EMAIL, signingKeyId = null))
        }

        // 남겨 두면 엉뚱한 키로 서명한다 — 지워서 전역 설정을 따르게 한다.
        localConfig(work).getString(USER, null, "signingkey") shouldBe null
        localConfig(work).getString(USER, null, "email") shouldBe PERSONAL_EMAIL
    }

    test("지정한 프로필이 다음 커밋의 author 가 된다") {
        val work = tempdir().also(::seedRepository)

        withIdentityGateway(work, settingsFile()) { gateway -> gateway.applyProfile(WORK) }
        val author = Git.open(work).use { git ->
            File(git.repository.workTree, CODE).appendText("적용 후 커밋\n")
            git.add().addFilepattern(CODE).call()
            // author 를 지정하지 않으면 JGit 이 저장소 설정에서 읽는다 — 그 경로가 이 계약의 소비처다.
            git.commit().setMessage("적용 후 커밋").call().authorIdent
        }

        author.name shouldBe WORK_PROFILE
        author.emailAddress shouldBe WORK_EMAIL
    }

    test("로컬 identity 를 제거하면 전역 설정을 따른다") {
        val work = tempdir().also(::seedRepository)

        withIdentityGateway(work, settingsFile()) { gateway ->
            gateway.applyProfile(WORK)
            gateway.clearLocalIdentity()
        }

        val local = localConfig(work)
        local.getString(USER, null, "name") shouldBe null
        local.getString(USER, null, "email") shouldBe null
        local.getString(USER, null, "signingkey") shouldBe null
        local.getString(UNDINE, null, IDENTITY_PROFILE) shouldBe null
        // 로컬 값이 사라졌으므로, 한 번도 손대지 않은 저장소와 똑같은 값으로 해석된다(= 전역을 따른다).
        effectiveEmail(work) shouldBe effectiveEmail(tempdir().also(::seedRepository))
    }

    test("저장한 프로필을 목록으로 읽고 같은 이름은 거부한다") {
        val work = tempdir().also(::seedRepository)
        val settingsFile = settingsFile()

        withIdentityGateway(work, settingsFile) { gateway ->
            gateway.profiles().shouldBeEmpty()
            gateway.saveProfile(WORK)
            gateway.profiles() shouldContainExactly listOf(WORK)

            shouldThrow<UndineException.StateViolation> {
                gateway.saveProfile(WORK.copy(email = PERSONAL_EMAIL))
            }
            // 거부된 저장이 기존 값을 덮지 않았다.
            gateway.profiles() shouldContainExactly listOf(WORK)
        }
    }

    test("프로필 삭제는 설정 목록만 바꾸고 저장소 로컬 설정은 그대로 둔다") {
        val work = tempdir().also(::seedRepository)
        val settingsFile = settingsFile()

        val assigned = withIdentityGateway(work, settingsFile) { gateway ->
            gateway.saveProfile(WORK)
            gateway.applyProfile(WORK)
            gateway.deleteProfile(WORK_PROFILE)
            gateway.profiles().shouldBeEmpty()
            gateway.assignedProfileName()
        }

        // 저장소를 훑어 로컬 설정을 지우지 않는다 — 사라진 이름은 '미지정' 으로 취급된다.
        assigned shouldBe WORK_PROFILE
        localConfig(work).getString(USER, null, "email") shouldBe WORK_EMAIL
    }

    test("없는 프로필을 지워도 설정을 망가뜨리지 않는다") {
        val work = tempdir().also(::seedRepository)
        val settingsFile = settingsFile()

        val remaining = withIdentityGateway(work, settingsFile) { gateway ->
            gateway.saveProfile(WORK)
            gateway.deleteProfile("없는 프로필")
            gateway.profiles()
        }

        remaining shouldContainExactly listOf(WORK)
    }

    test("원격 호스트는 origin 을 우선하고 userinfo·포트·대소문자를 지운다") {
        val work = tempdir().also(::seedRepository)
        addRemote(work, "backup", "https://backup.example/undine.git")
        addRemote(work, "origin", "ssh://git@GitHub.com:22/undine/undine.git")

        val host = withIdentityGateway(work, settingsFile()) { gateway -> gateway.remoteHost() }

        host shouldBe "github.com"
    }

    test("origin 이 없으면 첫 번째 원격을 본다") {
        val work = tempdir().also(::seedRepository)
        addRemote(work, "backup", "git@Company.example:undine/undine.git")

        val host = withIdentityGateway(work, settingsFile()) { gateway -> gateway.remoteHost() }

        host shouldBe "company.example"
    }

    test("원격이 없거나 호스트를 뽑을 수 없으면 판단하지 않는다") {
        val withoutRemote = tempdir().also(::seedRepository)
        val localRemote = tempdir().also(::seedRepository)
        addRemote(localRemote, "origin", tempdir().absolutePath)

        withIdentityGateway(withoutRemote, settingsFile()) { gateway -> gateway.remoteHost() } shouldBe null
        withIdentityGateway(localRemote, settingsFile()) { gateway -> gateway.remoteHost() } shouldBe null
    }

    test("동시 프로필 저장은 서로를 덮어쓰지 않는다") {
        val work = tempdir().also(::seedRepository)
        val added = (1..CONCURRENT_PROFILE_COUNT).map { order -> WORK.copy(name = "프로필 $order") }

        val saved = withInterleavedIdentityGateway(work, settingsFile()) { gateway ->
            coroutineScope {
                added.map { profile -> async(Dispatchers.IO) { gateway.saveProfile(profile) } }.awaitAll()
            }
            gateway.profiles()
        }

        saved shouldContainExactlyInAnyOrder added
    }

    test("동시 저장과 삭제가 교차해도 남을 프로필과 다른 설정을 잃지 않는다") {
        val work = tempdir().also(::seedRepository)
        val settingsFile = settingsFile()
        val keep = WORK.copy(name = "유지")
        val added = WORK.copy(name = "추가")

        val saved = withInterleavedIdentityGateway(work, settingsFile) { gateway ->
            gateway.saveProfile(WORK)
            gateway.saveProfile(keep)
            coroutineScope {
                launch(Dispatchers.IO) { gateway.saveProfile(added) }
                launch(Dispatchers.IO) { gateway.deleteProfile(WORK_PROFILE) }
            }
            gateway.profiles()
        }

        saved shouldContainExactlyInAnyOrder listOf(keep, added)
    }

    test("동시 삭제는 서로가 지운 프로필을 되살리지 않는다") {
        val work = tempdir().also(::seedRepository)
        val keep = WORK.copy(name = "유지")

        val saved = withInterleavedIdentityGateway(work, settingsFile()) { gateway ->
            gateway.saveProfile(WORK)
            gateway.saveProfile(keep)
            gateway.saveProfile(WORK.copy(name = "둘째"))
            coroutineScope {
                launch(Dispatchers.IO) { gateway.deleteProfile(WORK_PROFILE) }
                launch(Dispatchers.IO) { gateway.deleteProfile("둘째") }
            }
            gateway.profiles()
        }

        saved shouldContainExactly listOf(keep)
    }

    test("로컬 설정을 쓰지 못하면 프로필 적용이 Git 실패로 번역된다") {
        val work = tempdir().also(::seedRepository)
        blockConfigWrite(work)

        val failure = withIdentityGateway(work, settingsFile()) { gateway ->
            shouldThrow<UndineException.GitOperationFailed> { gateway.applyProfile(WORK) }
        }

        // 쓰지 못한 것을 성공으로 알리면 사용자는 신원이 바뀐 줄 안다.
        failure.operation shouldBe OPERATION_APPLY
        failure.cause.shouldBeInstanceOf<IOException>()
        localConfig(work).getString(USER, null, "email") shouldBe null
    }

    test("로컬 설정을 쓰지 못하면 로컬 identity 제거가 Git 실패로 번역된다") {
        val work = tempdir().also(::seedRepository)
        withIdentityGateway(work, settingsFile()) { gateway -> gateway.applyProfile(WORK) }
        blockConfigWrite(work)

        val failure = withIdentityGateway(work, settingsFile()) { gateway ->
            shouldThrow<UndineException.GitOperationFailed> { gateway.clearLocalIdentity() }
        }

        // 연산마다 다른 이름을 달아야 화면이 무엇이 실패했는지 말할 수 있다.
        failure.operation shouldBe OPERATION_CLEAR
        failure.cause.shouldBeInstanceOf<IOException>()
        // 지우지 못했으므로 로컬 값이 그대로 남는다 — 지운 것처럼 보이면 안 된다.
        localConfig(work).getString(USER, null, "email") shouldBe WORK_EMAIL
    }

    test("프로필 적용이 실패하면 같은 저장소의 후속 조회가 디스크와 갈라지지 않는다") {
        val work = tempdir().also(::seedRepository)

        val assigned = withIdentityGateway(work, settingsFile()) { gateway ->
            gateway.applyProfile(WORK)
            blockConfigWrite(work)
            shouldThrow<UndineException.GitOperationFailed> {
                gateway.applyProfile(WORK.copy(name = "개인", email = PERSONAL_EMAIL, signingKeyId = null))
            }
            // 같은 열린 저장소의 관측 상태 — 쓰지 못한 변경이 메모리에 남으면 여기서 '개인' 이 보인다.
            gateway.assignedProfileName()
        }

        assigned shouldBe localConfig(work).getString(UNDINE, null, IDENTITY_PROFILE)
        assigned shouldBe WORK_PROFILE
    }

    test("로컬 identity 제거가 실패하면 같은 저장소의 후속 조회가 디스크와 갈라지지 않는다") {
        val work = tempdir().also(::seedRepository)

        val assigned = withIdentityGateway(work, settingsFile()) { gateway ->
            gateway.applyProfile(WORK)
            blockConfigWrite(work)
            shouldThrow<UndineException.GitOperationFailed> { gateway.clearLocalIdentity() }
            // 지우지 못했으므로 지정은 그대로 보여야 한다 — 미지정으로 보이면 화면이 롤백됐다고 오해한다.
            gateway.assignedProfileName()
        }

        assigned shouldBe localConfig(work).getString(UNDINE, null, IDENTITY_PROFILE)
        assigned shouldBe WORK_PROFILE
    }

    test("취소는 Git 실패로 번역되지 않고 그대로 전파된다") {
        val work = tempdir().also(::seedRepository)

        val outcome = withOpenRepository(work) { gitAccess ->
            val gateway = IdentityGatewayImpl(gitAccess, SettingsGatewayImpl(settingsFile().toPath()))
            val release = CountDownLatch(1)
            val occupied = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Result<Unit>>()
            coroutineScope {
                // 저장소 접근 경계를 붙잡아 둔다 — 그동안 프로필 적용은 그 경계에서 기다린다.
                val busy = launch(Dispatchers.IO) {
                    gitAccess.withRepository {
                        occupied.complete(Unit)
                        release.await()
                    }
                }
                occupied.await()
                val waiting = launch(Dispatchers.IO) {
                    entered.complete(Unit)
                    finished.complete(runCatching { gateway.applyProfile(WORK) })
                }
                entered.await()
                waiting.cancel()
                finished.await().also {
                    release.countDown()
                    busy.join()
                }
            }
        }

        // IOException 만 번역한다 — 취소까지 GitOperationFailed 로 바꾸면 화면이 실패로 오해한다.
        outcome.exceptionOrNull().shouldBeInstanceOf<CancellationException>()
    }

    test("최근 50건 안의 다른 author 이메일만 불일치로 알린다") {
        // 51건 중 가장 나중 커밋은 HEAD 기준 1번째, 가장 오래된 커밋은 51번째다.
        val insideRange = tempdir().also { work -> seedCommitsWithForeignAuthorAt(work, order = SCAN_LIMIT + 1) }
        val outsideRange = tempdir().also { work -> seedCommitsWithForeignAuthorAt(work, order = 1) }

        val inside = withIdentityService(insideRange, settingsFile()) { service -> service.checkBeforeCommit() }
        val outside = withIdentityService(outsideRange, settingsFile()) { service -> service.checkBeforeCommit() }

        inside shouldContainExactly listOf(
            IdentityWarning.EmailMismatch(profileEmail = WORK_EMAIL, otherEmails = listOf(PERSONAL_EMAIL)),
        )
        outside.shouldBeEmpty()
    }
})

/** 테스트마다 새 설정 파일을 준다 — 프로필 목록이 테스트 사이에 새면 순서에 따라 결과가 달라진다. */
private fun settingsFile(): File = File.createTempFile("undine-settings", ".json")
    .also { file -> file.delete(); file.deleteOnExit() }

/** 저장소를 연 [GitAccess] 로 Gateway 를 만들어 [block] 을 수행하고, 성공·실패와 무관하게 닫는다. */
private suspend fun <T> withIdentityGateway(
    work: File,
    settingsFile: File,
    block: suspend (IdentityGatewayImpl) -> T,
): T = withOpenRepository(work) { gitAccess ->
    block(IdentityGatewayImpl(gitAccess, SettingsGatewayImpl(settingsFile.toPath())))
}

/** 실제 이력 조회까지 엮어 커밋 전 검사를 돌린다 — 50건 경계는 Mock 으로 검증할 수 없다. */
private suspend fun <T> withIdentityService(
    work: File,
    settingsFile: File,
    block: suspend (IdentityService) -> T,
): T =
    withOpenRepository(work) { gitAccess ->
        val gateway = IdentityGatewayImpl(gitAccess, SettingsGatewayImpl(settingsFile.toPath()))
        gateway.saveProfile(WORK)
        gateway.applyProfile(WORK)
        block(IdentityService(gateway, HistoryGatewayImpl(gitAccess)))
    }

/**
 * [withIdentityGateway] 와 같지만 설정 갱신 진입 직전에 양보하는 Gateway 를 끼운다 — 여러 갱신이
 * 겹치는 순간을 재현한다. 읽기-수정-쓰기가 한 임계구역 안에서 끝나지 않으면 오래된 스냅샷이
 * 방금 저장된 프로필을 덮어쓴다.
 */
private suspend fun <T> withInterleavedIdentityGateway(
    work: File,
    settingsFile: File,
    block: suspend (IdentityGatewayImpl) -> T,
): T = withOpenRepository(work) { gitAccess ->
    val settings = YieldingSettingsGateway(SettingsGatewayImpl(settingsFile.toPath()))
    block(IdentityGatewayImpl(gitAccess, settings))
}

/** 실제 설정 파일에 쓰면서 갱신 진입 전후로 [yield] 한다 — 갱신이 겹치는 틈을 재현하는 최소 장치다. */
private class YieldingSettingsGateway(private val delegate: SettingsGateway) : SettingsGateway {

    override suspend fun load(): Settings = delegate.load().also { yield() }

    override suspend fun save(settings: Settings) = delegate.save(settings)

    override suspend fun update(transform: (Settings) -> Settings) {
        yield()
        delegate.update(transform)
    }
}

private suspend fun <T> withOpenRepository(work: File, block: suspend (GitAccess) -> T): T {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(work.absolutePath)) { }
    return try {
        block(gitAccess)
    } finally {
        gitAccess.close()
    }
}

/** `.git/config.lock` 을 선점해 다음 로컬 설정 저장이 실패하게 한다 — 실제 쓰기 실패를 재현한다. */
private fun blockConfigWrite(work: File) {
    File(work, ".git/config.lock").createNewFile()
}

private fun seedRepository(work: File) {
    Git.init().setDirectory(work).setInitialBranch(INITIAL_BRANCH).call().use { git ->
        git.commitFile("첫 커밋", WORK_EMAIL)
    }
}

/**
 * 51건을 쌓되 [order] 번째로 만든 커밋만 다른 author 이메일을 쓴다.
 * `order = 1` 은 가장 오래된 커밋이라 HEAD 기준 51번째 — 최근 50건 **밖**이다.
 */
private fun seedCommitsWithForeignAuthorAt(work: File, order: Int) {
    Git.init().setDirectory(work).setInitialBranch(INITIAL_BRANCH).call().use { git ->
        repeat(SCAN_LIMIT + 1) { index ->
            val email = if (index + 1 == order) PERSONAL_EMAIL else WORK_EMAIL
            git.commitFile("커밋 ${index + 1}", email)
        }
    }
}

private fun Git.commitFile(message: String, authorEmail: String) {
    File(repository.workTree, CODE).appendText("$message\n")
    add().addFilepattern(CODE).call()
    val author = PersonIdent(WORK_PROFILE, authorEmail, Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    commit().setMessage(message).setAuthor(author).setCommitter(author).call()
}

private fun addRemote(work: File, name: String, url: String) {
    Git.open(work).use { git ->
        git.remoteAdd().setName(name).setUri(URIish(url)).call()
    }
}

/** base(전역·시스템) 없이 저장소 로컬 config 파일만 읽는다. */
private fun localConfig(work: File): FileBasedConfig =
    FileBasedConfig(File(work, ".git/config"), FS.DETECTED).also { config -> config.load() }

private fun effectiveEmail(work: File): String? =
    Git.open(work).use { git -> git.repository.config.getString(USER, null, "email") }

private fun globalConfigFile(): File = SystemReader.getInstance().openUserConfig(null, FS.DETECTED).file

private fun globalConfigSnapshot(): String? = globalConfigFile().takeIf { it.isFile }?.readText()
