package dev.undine.presentation.preferences

import dev.undine.domain.SettingsPreference
import dev.undine.domain.signing.SigningFormat
import dev.undine.domain.signing.SigningSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank

private val SIGNING = SigningSettings(
    signCommits = true,
    signTags = false,
    format = SigningFormat.SSH,
    signingKey = "~/.ssh/id_ed25519.pub",
)

class PreferencesRowSpec : FunSpec({

    test("앱 설정이 실효값인 행은 앱 출처로 표시하고 항목별 기본값 복원을 내준다") {
        val row = appPreferencesRow(
            label = PREFERENCES_TEST_STRINGS.theme,
            value = PREFERENCES_TEST_STRINGS.themeDark,
            preference = SettingsPreference.THEME,
            texts = PREFERENCES_TEST_STRINGS,
        )

        row.source shouldBe PreferenceValueSource.APP_SETTINGS
        row.sourceLabel shouldBe PREFERENCES_TEST_STRINGS.sourceApp
        row.canRestoreDefault shouldBe true
        row.restorablePreference shouldBe SettingsPreference.THEME
    }

    test("git 설정이 이기는 항목은 git 출처로 표시하고 기본값 복원을 내주지 않는다") {
        val row = gitConfigPreferencesRow(
            label = PREFERENCES_TEST_STRINGS.signingKey,
            value = "~/.ssh/id_ed25519.pub",
            texts = PREFERENCES_TEST_STRINGS,
        )

        row.source shouldBe PreferenceValueSource.GIT_CONFIG
        row.sourceLabel shouldBe PREFERENCES_TEST_STRINGS.sourceGit
        row.canRestoreDefault shouldBe false
        row.restorablePreference shouldBe null
    }

    test("두 출처의 표시 문구는 서로 다르다 — 무엇이 이기고 있는지 구분된다") {
        PREFERENCES_TEST_STRINGS.sourceApp shouldNotBe PREFERENCES_TEST_STRINGS.sourceGit
    }

    test("서명 행은 git 설정의 실효값을 읽기 전용으로 보여준다") {
        val rows = signingPreferencesRows(SIGNING, PREFERENCES_TEST_STRINGS)

        rows.map { it.source }.distinct() shouldBe listOf(PreferenceValueSource.GIT_CONFIG)
        rows.map { it.canRestoreDefault }.distinct() shouldBe listOf(false)
        rows.map { it.label }.forEach(String::shouldNotBeBlank)
        rows.map { it.value } shouldBe listOf(
            PREFERENCES_TEST_STRINGS.enabled,
            PREFERENCES_TEST_STRINGS.disabled,
            SigningFormat.SSH.name,
            "~/.ssh/id_ed25519.pub",
        )
    }

    test("서명 키가 설정돼 있지 않으면 미설정 문구를 보여준다") {
        val rows = signingPreferencesRows(SIGNING.copy(signingKey = null), PREFERENCES_TEST_STRINGS)

        rows.last().value shouldBe PREFERENCES_TEST_STRINGS.signingKeyUnset
    }

    test("서명 설정을 읽을 수 없으면 행을 만들지 않는다") {
        signingPreferencesRows(null, PREFERENCES_TEST_STRINGS) shouldBe emptyList()
    }

    test("좌우 이동은 탭 목록을 순환한다 — 키보드만으로 여섯 탭에 닿는다") {
        PreferencesTab.GENERAL.shifted(-1) shouldBe PreferencesTab.ADVANCED
        PreferencesTab.ADVANCED.shifted(1) shouldBe PreferencesTab.GENERAL
        PreferencesTab.GIT.shifted(1) shouldBe PreferencesTab.ACCOUNTS
        PreferencesTab.GIT.shifted(-1) shouldBe PreferencesTab.GENERAL
    }
})
