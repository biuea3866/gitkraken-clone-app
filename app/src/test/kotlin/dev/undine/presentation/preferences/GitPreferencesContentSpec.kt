package dev.undine.presentation.preferences

import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.AutomaticFetchSettings
import dev.undine.domain.PullStrategy
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.SettingsPreference
import dev.undine.domain.signing.SigningFormat
import dev.undine.domain.signing.SigningSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.IOException

private val STORED = Settings.DEFAULTS.copy(
    defaultBranchName = "trunk",
    pullStrategy = PullStrategy.REBASE,
    automaticFetch = AutomaticFetchSettings(enabled = true, intervalMinutes = 30),
)

private val SIGNING = SigningSettings(
    signCommits = true,
    signTags = false,
    format = SigningFormat.SSH,
    signingKey = "~/.ssh/id_ed25519.pub",
)

private val TEXTS = PREFERENCES_TEST_STRINGS

/**
 * 저장된 값을 들고 있는 가짜 Gateway.
 *
 * 변환은 저장 **전에** 적용한다 — 범위를 벗어난 값은 `Settings` 생성이 거부해 파일에 닿지 않는다.
 * [gate] 를 걸면 저장이 끝나지 않아, 화면이 저장 결과를 기다리는지 볼 수 있다.
 */
private class GitSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    var saveFailure: IOException? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        val updated = transform(stored)
        gate?.await()
        saveFailure?.let { throw it }
        stored = updated
    }
}

private class GitTabFixture(initial: Settings = STORED) {
    val gateway = GitSettingsGateway(initial)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    fun state(): PreferencesState = PreferencesState(
        scope = scope,
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
    ).also(PreferencesState::refresh)
}

/**
 * Git 탭이 조립하는 행과 편집 결과.
 *
 * Composable 을 띄우지 않고 **조립·변환 함수**를 직접 부른다 — 탭이 정하는 것은 어떤 행을 어떤
 * 출처로 내주는지와 값 변경이 어떤 `Settings` 가 되는지이고, 그 둘 다 Composable 밖 순수 함수다.
 */
class GitPreferencesContentSpec : FunSpec({

    test("앱 설정 네 항목을 앱 출처 행으로 조립한다 — 되돌릴 수 있는 것은 Git 설정 셋뿐이다") {
        val rows = listOf(
            defaultBranchNameRow(STORED, TEXTS),
            pullStrategyRow(STORED, TEXTS),
            automaticFetchRow(STORED, TEXTS),
            automaticFetchIntervalRow(STORED, TEXTS),
        )

        rows.map { it.source }.distinct() shouldContainExactly listOf(PreferenceValueSource.APP_SETTINGS)
        rows.map { it.label } shouldContainExactly listOf(
            TEXTS.defaultBranchName,
            TEXTS.pullStrategy,
            TEXTS.automaticFetch,
            TEXTS.automaticFetchInterval,
        )
        rows.mapNotNull { it.restorablePreference }.distinct() shouldContainExactly listOf(
            SettingsPreference.DEFAULT_BRANCH_NAME,
            SettingsPreference.PULL_STRATEGY,
            SettingsPreference.AUTOMATIC_FETCH,
        )
    }

    test("행 값은 저장된 설정을 그대로 보여 준다 — 기본 브랜치명·pull 방식·fetch 상태와 주기") {
        defaultBranchNameRow(STORED, TEXTS).value shouldBe "trunk"
        pullStrategyRow(STORED, TEXTS).value shouldBe TEXTS.pullStrategyRebase
        automaticFetchRow(STORED, TEXTS).value shouldBe TEXTS.enabled
        automaticFetchIntervalRow(STORED, TEXTS).value shouldBe "30"
    }

    test("merge 로 저장돼 있으면 pull 방식 행이 병합 문구를 보여 준다") {
        val merging = STORED.copy(pullStrategy = PullStrategy.MERGE)

        pullStrategyRow(merging, TEXTS).value shouldBe TEXTS.pullStrategyMerge
    }

    test("pull 방식과 fetch 상태는 저장이 끝난 뒤에야 화면에 반영된다") {
        val fixture = GitTabFixture()
        val state = fixture.state()
        val release = CompletableDeferred<Unit>()
        fixture.gateway.gate = release

        state.apply { it.copy(pullStrategy = PullStrategy.MERGE) }

        // 저장이 끝나기 전에는 낙관적으로 그리지 않는다 — 화면은 저장된 값에 머문다.
        state.settings.pullStrategy shouldBe PullStrategy.REBASE
        pullStrategyRow(state.settings, TEXTS).value shouldBe TEXTS.pullStrategyRebase

        release.complete(Unit)

        state.settings.pullStrategy shouldBe PullStrategy.MERGE
        pullStrategyRow(state.settings, TEXTS).value shouldBe TEXTS.pullStrategyMerge
        fixture.gateway.stored.pullStrategy shouldBe PullStrategy.MERGE
    }

    test("자동 fetch 를 끄면 주기 입력을 받지 않고 주기 값은 그대로 남는다") {
        val fixture = GitTabFixture()
        val state = fixture.state()

        state.apply { it.withAutomaticFetchEnabled(false) }

        state.settings.automaticFetch.enabled shouldBe false
        state.settings.acceptsFetchInterval shouldBe false
        // 되찾을 값을 잃지 않는다 — 껐다고 주기를 0 으로 만들지 않는다.
        state.settings.automaticFetch.intervalMinutes shouldBe 30
        automaticFetchIntervalRow(state.settings, TEXTS).value shouldBe "30"
    }

    test("다시 켜면 꺼 두기 전의 주기를 그대로 쓴다") {
        val fixture = GitTabFixture()
        val state = fixture.state()

        state.apply { it.withAutomaticFetchEnabled(false) }
        state.apply { it.withAutomaticFetchEnabled(true) }

        state.settings.acceptsFetchInterval shouldBe true
        state.settings.automaticFetch.intervalMinutes shouldBe 30
        fixture.gateway.stored.automaticFetch shouldBe AutomaticFetchSettings(enabled = true, intervalMinutes = 30)
    }

    test("자동 fetch 가 켜져 있으면 주기 입력을 받는다") {
        STORED.acceptsFetchInterval shouldBe true
    }

    test("주기 입력은 앞뒤 공백을 무시하고 분 단위 값으로 저장된다") {
        val fixture = GitTabFixture()
        val state = fixture.state()

        state.apply { it.withAutomaticFetchInterval(" 45 ") }

        state.settings.automaticFetch.intervalMinutes shouldBe 45
        fixture.gateway.stored.automaticFetch.intervalMinutes shouldBe 45
        state.saveFailure shouldBe null
    }

    test("양수가 아닌 주기는 탭이 아니라 domain 이 거부한다") {
        listOf("0", "-5").forEach { text ->
            shouldThrow<IllegalArgumentException> { STORED.withAutomaticFetchInterval(text) }
        }
    }

    test("숫자가 아닌 주기 입력도 양수가 아닌 값과 같은 경로로 거부된다") {
        listOf("", "   ", "십분").forEach { text ->
            shouldThrow<IllegalArgumentException> { STORED.withAutomaticFetchInterval(text) }
        }
    }

    test("거부된 주기는 저장되지 않고 기존 주기와 입력 오류 문구가 유지된다") {
        val fixture = GitTabFixture()
        val state = fixture.state()

        state.apply { it.withAutomaticFetchInterval("0") }

        state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.Rejected>()
        state.saveFailure?.messageIn(TEXTS) shouldBe TEXTS.invalidValue
        state.settings.automaticFetch shouldBe STORED.automaticFetch
        fixture.gateway.stored.automaticFetch shouldBe STORED.automaticFetch
    }

    test("빈 기본 브랜치명은 저장되지 않고 기존 이름과 입력 오류 문구가 유지된다") {
        val fixture = GitTabFixture()
        val state = fixture.state()

        state.apply { it.copy(defaultBranchName = "") }

        state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.Rejected>()
        state.saveFailure?.messageIn(TEXTS) shouldBe TEXTS.invalidValue
        defaultBranchNameRow(state.settings, TEXTS).value shouldBe "trunk"
        fixture.gateway.stored.defaultBranchName shouldBe "trunk"
    }

    test("설정 파일에 쓰지 못하면 기존 값과 쓰기 실패 문구가 유지된다") {
        val fixture = GitTabFixture()
        val state = fixture.state()
        fixture.gateway.saveFailure = IOException("디스크가 가득 찼습니다")

        state.apply { it.copy(pullStrategy = PullStrategy.MERGE) }

        state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.NotWritten>()
        state.saveFailure?.messageIn(TEXTS) shouldBe TEXTS.saveFailed
        state.settings.pullStrategy shouldBe PullStrategy.REBASE
        fixture.gateway.stored.pullStrategy shouldBe PullStrategy.REBASE
    }

    test("서명 행은 토글 없는 읽기 전용이다 — Git 탭은 서명 값을 쓰지 않는다") {
        val rows = signingPreferencesRows(SIGNING, TEXTS)

        rows.map { it.canRestoreDefault }.distinct() shouldContainExactly listOf(false)
        rows.map { it.source }.distinct() shouldContainExactly listOf(PreferenceValueSource.GIT_CONFIG)
    }

    test("서명 실효값을 읽을 수 없으면 서명 행이 아예 없다 — 꺼진 것으로 보여 주지 않는다") {
        signingPreferencesRows(null, TEXTS) shouldBe emptyList()
    }
})
