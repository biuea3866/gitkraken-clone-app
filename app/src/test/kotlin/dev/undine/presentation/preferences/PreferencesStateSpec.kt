package dev.undine.presentation.preferences

import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.LoadSigningPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.ExternalTool
import dev.undine.domain.ExternalToolSettings
import dev.undine.domain.IdentityProfile
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.SettingsPreference
import dev.undine.domain.ShortcutBinding
import dev.undine.domain.ShortcutModifierKey
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.domain.UpdateCheckSettings
import dev.undine.domain.signing.SigningFormat
import dev.undine.domain.signing.SigningGateway
import dev.undine.domain.signing.SigningSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.awt.event.KeyEvent
import java.io.IOException

private val PROFILE = IdentityProfile(
    name = "회사 계정",
    email = "work@example.com",
    signingKeyId = null,
    defaultAuthentication = AuthenticationMethod.SSH,
    expectedHost = null,
)

private val STORED = Settings.DEFAULTS.copy(
    recentRepositories = listOf(RepositoryPath("/tmp/repo")),
    theme = ThemeMode.DARK,
    identityProfiles = listOf(PROFILE),
    externalTools = ExternalToolSettings(
        diffTool = ExternalTool(executable = "meld", arguments = listOf("local")),
        mergeTool = null,
    ),
    language = "en-GB",
    confirmDestructiveActions = false,
    openTabs = listOf(RepositoryPath("/tmp/tab")),
    updateCheck = UpdateCheckSettings(enabled = false, intervalHours = 72),
    shortcutOverrides = mapOf(
        "graph.refresh" to ShortcutBinding(keyCode = KeyEvent.VK_R, modifiers = setOf(ShortcutModifierKey.PRIMARY)),
    ),
)

private val SIGNING = SigningSettings(
    signCommits = true,
    signTags = false,
    format = SigningFormat.SSH,
    signingKey = "~/.ssh/id_ed25519.pub",
)

/** 저장된 값을 들고 있는 가짜 Gateway. 실패를 켜면 읽기·쓰기가 모두 [IOException] 을 던진다. */
private class FakeSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    var loadFailure: IOException? = null
    var saveFailure: IOException? = null

    override suspend fun load(): Settings = loadFailure?.let { throw it } ?: stored

    override suspend fun save(settings: Settings) {
        saveFailure?.let { throw it }
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        saveFailure?.let { throw it }
        stored = transform(stored)
    }
}

/**
 * 저장 요청을 테스트가 풀어 줄 때까지 붙잡는 Gateway. 저장이 끝나지 않은 동안 다음 변경·읽기를
 * 걸쳐 두고, **진입 순서가 누른 순서와 같은지**와 화면·파일이 마지막 변경으로 모이는지 본다 —
 * 실제 디스크 저장은 곧바로 끝나지 않으므로 그 사이에 다음 조작이 들어온다.
 */
private class GatedSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    /** 시작된 순서대로 쌓이는 저장 요청. 테스트가 원하는 순서로 완료·실패시킨다. */
    val pendingSaves = mutableListOf<CompletableDeferred<Unit>>()

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        awaitRelease()
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        awaitRelease()
        stored = transform(stored)
    }

    /** 실패로 완료되면 그 예외가 그대로 호출부로 올라간다 — 저장 실패 경로와 같다. */
    private suspend fun awaitRelease() {
        val release = CompletableDeferred<Unit>()
        pendingSaves += release
        release.await()
    }
}

/**
 * 읽기 요청을 테스트가 풀어 줄 때까지 붙잡는 Gateway. 읽기는 저장·다른 읽기와 겹칠 수 있고
 * 시작 순서대로 끝나지도 않으므로, 완료 순서를 뒤집어 늦게 끝난 읽기가 최신 상태를 덮는지 본다.
 */
private class GatedLoadSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    /** 시작된 순서대로 쌓이는 읽기 요청. 테스트가 원하는 순서로 완료·실패시킨다. */
    val pendingLoads = mutableListOf<CompletableDeferred<Settings>>()

    override suspend fun load(): Settings {
        val release = CompletableDeferred<Settings>()
        pendingLoads += release
        return release.await()
    }

    override suspend fun save(settings: Settings) {
        stored = settings
    }

    /** 한 번만 저장을 실패시킨다. 읽기가 대기 중인 동안의 저장 실패를 재현하려고 둔다. */
    var nextSaveFailure: IOException? = null

    override suspend fun update(transform: (Settings) -> Settings) {
        nextSaveFailure?.let {
            nextSaveFailure = null
            throw it
        }
        stored = transform(stored)
    }
}

/** 읽기가 끝나는 시점을 테스트가 정하는 상태 홀더. 저장은 곧바로 끝난다. */
private class GatedLoadFixture {
    val gateway = GatedLoadSettingsGateway(STORED)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    fun state(): PreferencesState = PreferencesState(
        scope = scope,
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
    )
}

/** 저장이 끝나는 시점을 테스트가 정하는 상태 홀더. 저장이 진행 중일 때 다음 조작을 걸쳐 둔다. */
private class GatedStateFixture {
    val gateway = GatedSettingsGateway(STORED)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    fun state(): PreferencesState = PreferencesState(
        scope = scope,
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
    )

    /**
     * 두 변경을 잇달아 누른 상태. 두 번째 저장은 첫 저장이 끝나기 전에는 `update` 에 **들어가지
     * 않는다** — 진입 순서가 곧 누른 순서라야 파일에 마지막 변경이 남는다.
     */
    fun stateWithQueuedSaves(): PreferencesState {
        val state = state()
        state.refresh()
        state.apply { it.copy(theme = ThemeMode.LIGHT) }
        state.apply { it.copy(theme = ThemeMode.SYSTEM) }
        gateway.pendingSaves.size shouldBe 1
        return state
    }
}

private class PreferencesStateFixture(initial: Settings = STORED) {
    val gateway = FakeSettingsGateway(initial)
    val signingGateway = mockk<SigningGateway>()
    val job = Job()
    val scope = CoroutineScope(Dispatchers.Unconfined + job)

    init {
        coEvery { signingGateway.settings() } returns SIGNING
    }

    fun state(withSigning: Boolean = false) = PreferencesState(
        scope = scope,
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
        loadSigningPreferences = if (withSigning) LoadSigningPreferencesUseCase(signingGateway) else null,
    )
}

class PreferencesStateSpec : FunSpec({

    test("화면은 일반 탭에서 열리고 저장된 설정을 읽어 온다") {
        val fixture = PreferencesStateFixture()
        val state = fixture.state()

        state.refresh()

        state.selectedTab shouldBe PreferencesTab.GENERAL
        state.settings shouldBe STORED
        state.loadFailure shouldBe null
    }

    test("탭을 전환하면 선택 탭의 내용만 그린다") {
        val state = PreferencesStateFixture().state()

        PreferencesTab.entries.forEach { tab ->
            state.selectTab(tab)

            state.selectedTab shouldBe tab
            PreferencesTab.entries.filter(state::rendersContent) shouldContainExactly listOf(tab)
        }
    }

    test("여섯 개 탭이 모두 제공된다") {
        PreferencesTab.entries shouldContainExactly listOf(
            PreferencesTab.GENERAL,
            PreferencesTab.GIT,
            PreferencesTab.ACCOUNTS,
            PreferencesTab.TOOLS,
            PreferencesTab.SHORTCUTS,
            PreferencesTab.ADVANCED,
        )
    }

    test("값을 바꾸면 저장 버튼 없이 곧바로 반영·저장된다") {
        val fixture = PreferencesStateFixture()
        val state = fixture.state()
        state.refresh()

        state.apply { it.copy(theme = ThemeMode.LIGHT) }

        state.settings.theme shouldBe ThemeMode.LIGHT
        fixture.gateway.stored.theme shouldBe ThemeMode.LIGHT
        state.saveFailure shouldBe null
    }

    test("즉시 저장이 실패하면 화면이 이전 값으로 돌아가고 실패가 표시된다") {
        val fixture = PreferencesStateFixture()
        val state = fixture.state()
        state.refresh()
        fixture.gateway.saveFailure = IOException("디스크가 가득 찼습니다")

        state.apply { it.copy(theme = ThemeMode.LIGHT) }

        state.settings.theme shouldBe ThemeMode.DARK
        state.saveFailure.shouldNotBeNull()
        fixture.gateway.stored shouldBe STORED
    }

    test("설정을 읽지 못하면 기본값 상태로 화면을 열고 사유를 남긴다") {
        val fixture = PreferencesStateFixture()
        fixture.gateway.loadFailure = IOException("설정 파일을 읽을 수 없습니다")
        val state = fixture.state()

        state.refresh()

        state.settings shouldBe Settings.DEFAULTS
        state.loadFailure.shouldNotBeNull()
        state.selectedTab shouldBe PreferencesTab.GENERAL
    }

    test("항목별 기본값 복원은 그 항목만 되돌리고 다른 항목은 유지한다") {
        val fixture = PreferencesStateFixture()
        val state = fixture.state()
        state.refresh()

        state.restoreDefault(SettingsPreference.LANGUAGE)

        state.settings.language shouldBe null
        state.settings.theme shouldBe ThemeMode.DARK
        state.settings.confirmDestructiveActions shouldBe false
        fixture.gateway.stored.language shouldBe null
        fixture.gateway.stored.theme shouldBe ThemeMode.DARK
    }

    test("전체 초기화는 확인을 받은 뒤에만 수행된다") {
        val fixture = PreferencesStateFixture()
        val state = fixture.state()
        state.refresh()

        state.requestResetAll()
        state.isResetConfirmationVisible shouldBe true
        fixture.gateway.stored shouldBe STORED

        state.cancelResetAll()
        state.isResetConfirmationVisible shouldBe false
        fixture.gateway.stored shouldBe STORED
    }

    test("확인된 전체 초기화는 취향과 탭 세션만 되돌리고 프로필·도구는 남긴다") {
        val fixture = PreferencesStateFixture()
        val state = fixture.state()
        state.refresh()

        state.requestResetAll()
        state.confirmResetAll()

        state.isResetConfirmationVisible shouldBe false
        state.settings.theme shouldBe Settings.DEFAULT_THEME
        state.settings.language shouldBe null
        state.settings.openTabs shouldBe emptyList()
        state.settings.shortcutOverrides.shouldBeEmpty()
        state.settings.identityProfiles shouldContainExactly listOf(PROFILE)
        state.settings.externalTools shouldBe STORED.externalTools
        state.settings.recentRepositories shouldContainExactly listOf(RepositoryPath("/tmp/repo"))
    }

    test("서명 설정을 읽을 수 있으면 git 설정 출처의 읽기 전용 행을 내준다") {
        val state = PreferencesStateFixture().state(withSigning = true)

        state.refresh()

        state.signing shouldBe SIGNING
        signingPreferencesRows(state.signing, PREFERENCES_TEST_STRINGS).forEach { row ->
            row.source shouldBe PreferenceValueSource.GIT_CONFIG
            row.canRestoreDefault shouldBe false
        }
    }

    test("저장소가 열려 있지 않으면 서명 행이 비어 있다") {
        val state = PreferencesStateFixture().state(withSigning = false)

        state.refresh()

        state.signing shouldBe null
        signingPreferencesRows(state.signing, PREFERENCES_TEST_STRINGS) shouldBe emptyList()
    }

    test("겹쳐 누른 두 변경은 누른 순서대로 저장되고 화면과 파일이 마지막 변경으로 모인다") {
        val fixture = GatedStateFixture()
        val state = fixture.stateWithQueuedSaves()

        fixture.gateway.pendingSaves[0].complete(Unit)
        fixture.gateway.stored.theme shouldBe ThemeMode.LIGHT
        fixture.gateway.pendingSaves.size shouldBe 2
        fixture.gateway.pendingSaves[1].complete(Unit)

        state.settings.theme shouldBe ThemeMode.SYSTEM
        fixture.gateway.stored.theme shouldBe ThemeMode.SYSTEM
        state.saveFailure shouldBe null
    }

    test("앞선 저장이 실패해도 최신 값을 되돌리지 않고 파일도 최신 변경으로 모인다") {
        val fixture = GatedStateFixture()
        val state = fixture.stateWithQueuedSaves()

        fixture.gateway.pendingSaves[0].completeExceptionally(IOException("디스크가 가득 찼습니다"))
        fixture.gateway.pendingSaves[1].complete(Unit)

        state.settings.theme shouldBe ThemeMode.SYSTEM
        fixture.gateway.stored.theme shouldBe ThemeMode.SYSTEM
        state.saveFailure shouldBe null
    }

    test("최신 저장이 실패하면 그 시점의 이전 값으로 되돌리고 실패를 표시한다") {
        val fixture = GatedStateFixture()
        val state = fixture.stateWithQueuedSaves()

        fixture.gateway.pendingSaves[0].complete(Unit)
        fixture.gateway.pendingSaves[1].completeExceptionally(IOException("디스크가 가득 찼습니다"))

        state.settings.theme shouldBe ThemeMode.LIGHT
        fixture.gateway.stored.theme shouldBe ThemeMode.LIGHT
        state.saveFailure.shouldNotBeNull()
    }

    test("화면이 파일보다 낡았으면 무변경으로 보이는 조작도 저장 경로를 지난다") {
        val fixture = GatedLoadFixture()
        val state = fixture.state()

        // 화면은 아직 아무것도 못 읽었고(기본값), 파일에는 다른 값이 들어 있다.
        state.apply { it.copy(theme = Settings.DEFAULTS.theme) }

        // 화면 기준으로는 "변경 없음" 이지만, 저장은 파일의 값 위에서 수행돼야 한다.
        fixture.gateway.stored.theme shouldBe Settings.DEFAULTS.theme
        state.settings.theme shouldBe Settings.DEFAULTS.theme
    }

    test("연속 두 저장이 모두 실패해도 화면은 저장된 값에 머문다") {
        val fixture = GatedStateFixture()
        val state = fixture.stateWithQueuedSaves()

        fixture.gateway.pendingSaves[0].completeExceptionally(IOException("디스크가 가득 찼습니다"))
        fixture.gateway.pendingSaves[1].completeExceptionally(IOException("디스크가 가득 찼습니다"))

        // 낙관적으로 그리지 않으므로 되돌릴 대상이 없다 — 화면은 처음부터 저장된 값만 보여 준다.
        state.settings.theme shouldBe STORED.theme
        fixture.gateway.stored.theme shouldBe STORED.theme
        state.saveFailure.shouldNotBeNull()
    }

    test("첫 읽기가 끝나기 전에 누른 변경이 실패해도 화면과 파일이 갈리지 않는다") {
        val fixture = GatedLoadFixture()
        val state = fixture.state()

        state.refresh()
        fixture.gateway.nextSaveFailure = IOException("디스크가 가득 찼습니다")
        state.apply { it.copy(theme = ThemeMode.LIGHT) }
        fixture.gateway.pendingLoads[0].complete(STORED)

        state.settings.theme shouldBe fixture.gateway.stored.theme
        state.saveFailure.shouldNotBeNull()
    }

    test("저장이 끝나기 전에 다시 읽어도 화면이 저장된 파일과 갈리지 않는다") {
        val fixture = GatedStateFixture()
        val state = fixture.state()

        state.apply { it.copy(theme = ThemeMode.LIGHT) }
        state.refresh()
        fixture.gateway.pendingSaves.single().complete(Unit)

        state.settings.theme shouldBe ThemeMode.LIGHT
        fixture.gateway.stored.theme shouldBe ThemeMode.LIGHT
        state.loadFailure shouldBe null
        state.saveFailure shouldBe null
    }

    test("서명 실효값을 읽지 못하면 행을 조용히 비우지 않고 사유를 내보낸다") {
        val fixture = PreferencesStateFixture()
        coEvery { fixture.signingGateway.settings() } throws UndineException.GitOperationFailed("서명 설정 조회")
        val state = fixture.state(withSigning = true)

        state.refresh()

        state.signing shouldBe null
        state.signingFailure.shouldNotBeNull()
        signingPreferencesRows(state.signing, PREFERENCES_TEST_STRINGS) shouldBe emptyList()
    }

    test("늦게 끝난 설정 읽기는 그 사이에 저장된 최신 값을 덮지 않는다") {
        val fixture = GatedLoadFixture()
        val state = fixture.state()

        state.refresh()
        state.apply { it.copy(theme = ThemeMode.LIGHT) }
        fixture.gateway.pendingLoads.single().complete(STORED)

        state.settings shouldBe STORED.copy(theme = ThemeMode.LIGHT)
        state.loadFailure shouldBe null
        state.saveFailure shouldBe null
    }

    test("앞선 설정 읽기가 실패해도 뒤이은 읽기 결과를 기본값으로 덮지 않는다") {
        val fixture = GatedLoadFixture()
        val state = fixture.state()

        state.refresh()
        state.refresh()
        fixture.gateway.pendingLoads[0].completeExceptionally(IOException("설정 파일을 읽을 수 없습니다"))
        fixture.gateway.pendingLoads[1].complete(STORED)

        state.settings shouldBe STORED
        state.loadFailure shouldBe null
    }

    test("앞선 서명 읽기가 늦게 실패해도 최신 읽기의 실효값을 지우지 않는다") {
        val fixture = PreferencesStateFixture()
        val firstRead = CompletableDeferred<SigningSettings>()
        val secondRead = CompletableDeferred<SigningSettings>()
        val pendingReads = ArrayDeque(listOf(firstRead, secondRead))
        coEvery { fixture.signingGateway.settings() } coAnswers { pendingReads.removeFirst().await() }
        val state = fixture.state(withSigning = true)

        state.refresh()
        state.refresh()
        secondRead.complete(SIGNING)
        firstRead.completeExceptionally(UndineException.GitOperationFailed("서명 설정 조회"))

        state.signing shouldBe SIGNING
        state.signingFailure shouldBe null
    }

    test("설정 저장이 겹쳐도 그 사이에 읽어 온 서명 실효값은 버리지 않는다") {
        val fixture = PreferencesStateFixture()
        val release = CompletableDeferred<SigningSettings>()
        coEvery { fixture.signingGateway.settings() } coAnswers { release.await() }
        val state = fixture.state(withSigning = true)

        state.refresh()
        state.apply { it.copy(theme = ThemeMode.LIGHT) }
        release.complete(SIGNING)

        state.signing shouldBe SIGNING
        state.signingFailure shouldBe null
    }

    test("서명 실효값을 다시 읽는 데 성공하면 앞선 실패 사유가 지워진다") {
        val fixture = PreferencesStateFixture()
        coEvery { fixture.signingGateway.settings() } throws UndineException.GitOperationFailed("서명 설정 조회")
        val state = fixture.state(withSigning = true)
        state.refresh()
        state.signingFailure.shouldNotBeNull()

        coEvery { fixture.signingGateway.settings() } returns SIGNING
        state.refresh()

        state.signing shouldBe SIGNING
        state.signingFailure shouldBe null
    }
})
