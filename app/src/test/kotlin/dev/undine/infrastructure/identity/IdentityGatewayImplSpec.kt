package dev.undine.infrastructure.identity

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.IdentityProfile
import dev.undine.domain.Person
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.UndineException
import dev.undine.domain.identity.GlobalIdentity
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
import org.eclipse.jgit.lib.StoredConfig
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
private const val PERSONAL_PROFILE = "개인"
private const val RENAMED_PROFILE = "회사(신)"
private const val OTHER_SIGNING_KEY = "FFFF9999"

/** 닫히지 않은 섹션 머리 — git 도 JGit 도 파싱하지 못한다. */
private const val BROKEN_CONFIG_TEXT = "[user\n"
private const val GLOBAL_EMAIL = "global@example.com"

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

private val PERSONAL = IdentityProfile(
    name = PERSONAL_PROFILE,
    email = PERSONAL_EMAIL,
    signingKeyId = null,
    defaultAuthentication = AuthenticationMethod.HTTPS,
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

    test("같은 프로필을 쓰는 최근 저장소가 둘이면 집계가 2 다") {
        val first = tempdir().also(::seedRepository).also { work -> assignProfile(work, WORK_PROFILE) }
        val second = tempdir().also(::seedRepository).also { work -> assignProfile(work, WORK_PROFILE) }
        val other = tempdir().also(::seedRepository).also { work -> assignProfile(work, "개인") }
        val untouched = tempdir().also(::seedRepository)
        val open = tempdir().also(::seedRepository)

        val usage = withUsageGateway(
            open = open,
            candidates = listOf(first, second, other, untouched),
            globalIdentityConfig = globalIdentityConfig(name = "전역 사용자", email = GLOBAL_EMAIL),
        ) { gateway -> gateway.profileUsage(WORK_PROFILE) }

        usage.repositoryCount shouldBe 2
        usage.uncheckedRepositoryCount shouldBe 0
        usage.globalIdentity shouldBe GlobalIdentity.Configured(Person("전역 사용자", GLOBAL_EMAIL))
    }

    test("아무도 쓰지 않는 프로필과 빈 후보 목록의 집계는 0 이고 실패하지 않는다") {
        val open = tempdir().also(::seedRepository)
        val untouched = tempdir().also(::seedRepository)

        val unused = withUsageGateway(open, listOf(untouched), emptyConfig()) { gateway ->
            gateway.profileUsage(WORK_PROFILE)
        }
        val noCandidates = withUsageGateway(open, emptyList(), emptyConfig()) { gateway ->
            gateway.profileUsage(WORK_PROFILE)
        }

        unused.repositoryCount shouldBe 0
        noCandidates.repositoryCount shouldBe 0
    }

    test("사라졌거나 저장소가 아닌 후보는 실패가 아니라 세지 않고 넘어간다") {
        val open = tempdir().also(::seedRepository)
        val using = tempdir().also(::seedRepository).also { work -> assignProfile(work, WORK_PROFILE) }
        val notARepository = tempdir()
        val vanished = tempdir().also { work -> work.deleteRecursively() }

        val usage = withUsageGateway(
            open = open,
            candidates = listOf(using, notARepository, vanished),
            globalIdentityConfig = emptyConfig(),
        ) { gateway -> gateway.profileUsage(WORK_PROFILE) }

        // 저장소가 아닌 후보는 확인할 저장소 자체가 없다 — 미확인 수를 부풀리지 않는다.
        usage.repositoryCount shouldBe 1
        usage.uncheckedRepositoryCount shouldBe 0
    }

    test("같은 저장소를 가리키는 별칭 경로는 한 번만 센다") {
        val work = tempdir().also(::seedRepository).also { path -> assignProfile(path, WORK_PROFILE) }
        val open = tempdir().also(::seedRepository)
        val alias = File(work, ".${File.separator}")

        val usage = withUsageGateway(
            open = open,
            candidates = listOf(work, alias),
            globalIdentityConfig = emptyConfig(),
        ) { gateway -> gateway.profileUsage(WORK_PROFILE) }

        usage.repositoryCount shouldBe 1
    }

    test("전역 identity 가 설정돼 있지 않으면 실패 대신 '설정 안 함' 이다") {
        val open = tempdir().also(::seedRepository)

        val missing = withUsageGateway(open, emptyList(), emptyConfig()) { gateway ->
            gateway.profileUsage(WORK_PROFILE)
        }
        // 이름만 있는 반쪽 설정으로는 git 이 커밋할 수 없다 — '설정 없음' 과 같게 다룬다.
        val halfConfigured = withUsageGateway(
            open = open,
            candidates = emptyList(),
            globalIdentityConfig = globalIdentityConfig(name = "이름만", email = null),
        ) { gateway -> gateway.profileUsage(WORK_PROFILE) }

        missing.globalIdentity shouldBe GlobalIdentity.NotConfigured
        halfConfigured.globalIdentity shouldBe GlobalIdentity.NotConfigured
    }

    test("전역 설정을 읽지 못하면 '설정 안 함' 이 아니라 '읽지 못함' 이다") {
        val open = tempdir().also(::seedRepository)

        // 파일을 열지 못하는 경우 — 삭제 확인이 막히지 않아야 한다.
        val unreadable = withUsageGateway(open, emptyList(), failingConfig()) { gateway ->
            gateway.profileUsage(WORK_PROFILE)
        }
        // 파싱하지 못하는 경우 — 닫히지 않은 섹션 머리는 git 도 읽지 못한다.
        val invalid = withUsageGateway(open, emptyList(), brokenConfig()) { gateway ->
            gateway.profileUsage(WORK_PROFILE)
        }

        // '없다' 고 말하면 삭제 확인 화면이 있는 신원을 없다고 알린다 (결정 G36).
        unreadable.globalIdentity shouldBe GlobalIdentity.Unreadable
        invalid.globalIdentity shouldBe GlobalIdentity.Unreadable
    }

    test("로컬 config 를 읽지 못한 후보는 실패도 미사용도 아니라 미확인으로 센다") {
        val open = tempdir().also(::seedRepository)
        val using = tempdir().also(::seedRepository).also { work -> assignProfile(work, WORK_PROFILE) }
        val broken = tempdir().also(::seedRepository).also(::breakLocalConfig)

        val usage = withUsageGateway(
            open = open,
            candidates = listOf(using, broken),
            globalIdentityConfig = emptyConfig(),
        ) { gateway -> gateway.profileUsage(WORK_PROFILE) }

        // 깨진 후보 하나가 집계 전체를 실패로 만들지 않되, 전수가 아님을 화면이 알 수 있어야 한다.
        usage.repositoryCount shouldBe 1
        usage.uncheckedRepositoryCount shouldBe 1
    }

    test("같은 저장소를 가리키는 별칭 경로는 미확인 집계에서도 한 번만 센다") {
        val open = tempdir().also(::seedRepository)
        val broken = tempdir().also(::seedRepository).also(::breakLocalConfig)
        val alias = File(broken, ".${File.separator}")

        val usage = withUsageGateway(
            open = open,
            candidates = listOf(broken, alias),
            globalIdentityConfig = emptyConfig(),
        ) { gateway -> gateway.profileUsage(WORK_PROFILE) }

        usage.uncheckedRepositoryCount shouldBe 1
    }

    test("이미 저장된 잘못된 형식의 이메일도 그대로 읽힌다") {
        val open = tempdir().also(::seedRepository)
        val settings = settingsFile()
        val stored = WORK.copy(email = "예전에 저장된 값")
        SettingsGatewayImpl(settings.toPath())
            .save(Settings.DEFAULTS.copy(identityProfiles = listOf(stored)))

        val profiles = withIdentityGateway(open, settings) { gateway -> gateway.profiles() }

        profiles shouldContainExactly listOf(stored)
    }

    test("같은 이름 프로필의 이메일과 서명 키를 하나의 update 로 바꾼다") {
        val work = tempdir().also(::seedRepository)
        val settings = settingsFile()
        val updated = WORK.copy(email = PERSONAL_EMAIL, signingKeyId = OTHER_SIGNING_KEY)

        val profiles = withIdentityGateway(work, settings) { gateway ->
            gateway.saveProfile(WORK)
            gateway.updateProfile(WORK_PROFILE, updated)
            gateway.profiles()
        }

        profiles shouldContainExactly listOf(updated)
    }

    // 저장소들은 로컬 설정에 프로필 '이름' 으로 연결을 적어 둔다 — 이름을 바꾸면 그 참조가 옛
    // 이름을 가리킨 채 남아, 지운 것과 같은 결과가 된다 (결정 G38).
    test("이름을 바꾸려는 수정은 거부하고 기존 프로필을 삭제된 상태로 남기지 않는다") {
        val work = tempdir().also(::seedRepository)
        val settings = settingsFile()

        val profiles = withIdentityGateway(work, settings) { gateway ->
            gateway.saveProfile(WORK)
            gateway.saveProfile(PERSONAL)
            // 아무도 쓰지 않는 새 이름이어도 거부한다 — 참조 이관은 이 연산의 범위가 아니다.
            val renaming = shouldThrow<UndineException.StateViolation> {
                gateway.updateProfile(WORK_PROFILE, WORK.copy(name = RENAMED_PROFILE))
            }
            renaming.detail shouldBe
                "신원 프로필의 이름은 수정으로 바꿀 수 없습니다: '$WORK_PROFILE' → '$RENAMED_PROFILE'"
            // 이미 다른 프로필이 쓰는 이름으로 바꾸려는 요청도 같은 이유로 거부된다.
            shouldThrow<UndineException.StateViolation> {
                gateway.updateProfile(WORK_PROFILE, WORK.copy(name = PERSONAL_PROFILE))
            }
            gateway.profiles()
        }

        profiles shouldContainExactlyInAnyOrder listOf(WORK, PERSONAL)
    }

    test("없는 프로필을 고치려 하면 새로 만들지 않고 거부한다") {
        val work = tempdir().also(::seedRepository)
        val settings = settingsFile()

        val profiles = withIdentityGateway(work, settings) { gateway ->
            shouldThrow<UndineException.StateViolation> { gateway.updateProfile(WORK_PROFILE, WORK) }
            gateway.profiles()
        }

        profiles.shouldBeEmpty()
    }
})

/**
 * 사용 집계용 Gateway — 후보 저장소 목록을 설정에 심고 **전역 설정을 임시 파일로 갈아 끼운다**.
 *
 * 실제 사용자의 `~/.gitconfig` 를 읽으면 결과가 기계마다 달라진다. 전역 파일은 여기서도 **읽기만**
 * 하고, 후보 저장소들은 열린 저장소와 별개라 `GitAccess` 를 지나지 않는다.
 */
private suspend fun <T> withUsageGateway(
    open: File,
    candidates: List<File>,
    globalIdentityConfig: StoredConfig,
    block: suspend (IdentityGatewayImpl) -> T,
): T = withOpenRepository(open) { gitAccess ->
    val settingsGateway = SettingsGatewayImpl(settingsFile().toPath())
    settingsGateway.save(
        Settings.DEFAULTS.copy(
            recentRepositories = candidates.map { candidate -> RepositoryPath(candidate.path) },
        ),
    )
    block(IdentityGatewayImpl(gitAccess, settingsGateway) { globalIdentityConfig })
}

/** 후보 저장소의 **로컬** 설정에 프로필 이름을 심는다 — 집계가 읽는 바로 그 키다. */
private fun assignProfile(work: File, profileName: String) {
    localConfig(work).also { config ->
        config.setString(UNDINE, null, IDENTITY_PROFILE, profileName)
        config.save()
    }
}

private fun globalIdentityConfig(name: String?, email: String?): FileBasedConfig =
    FileBasedConfig(temporaryConfigFile(), FS.DETECTED).also { config ->
        name?.let { value -> config.setString(USER, null, "name", value) }
        email?.let { value -> config.setString(USER, null, "email", value) }
        config.save()
    }

/** 파일이 아예 없는 전역 설정 — "설정 없음" 경로를 재현한다. */
private fun emptyConfig(): FileBasedConfig = FileBasedConfig(temporaryConfigFile(), FS.DETECTED)

/**
 * 파싱할 수 없는 전역 설정 — 닫히지 않은 섹션 머리는 `ConfigInvalidException` 을 낸다.
 * 실제 사용자가 `~/.gitconfig` 를 손으로 고치다 만드는 상태다.
 */
private fun brokenConfig(): FileBasedConfig =
    FileBasedConfig(temporaryConfigFile().also { file -> file.writeText(BROKEN_CONFIG_TEXT) }, FS.DETECTED)

/**
 * 열지 못하는 전역 설정 — 권한이 없거나 디스크를 읽지 못하는 경우를 `IOException` 으로 재현한다.
 * 파일로는 플랫폼마다 재현이 갈려서 읽기 경계 자체를 갈아 끼운다.
 */
private fun failingConfig(): StoredConfig = object : StoredConfig() {
    override fun load(): Unit = throw IOException("전역 설정을 읽지 못했다")

    override fun save(): Unit = throw UnsupportedOperationException("전역 설정에는 쓰지 않는다")
}

/** 후보 저장소의 **로컬** config 를 파싱 불가 상태로 만든다 — 미확인 후보 경로를 재현한다. */
private fun breakLocalConfig(work: File) {
    File(File(work, ".git"), "config").writeText(BROKEN_CONFIG_TEXT)
}

private fun temporaryConfigFile(): File = File.createTempFile("undine-global", ".gitconfig")
    .also { file -> file.delete(); file.deleteOnExit() }

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
