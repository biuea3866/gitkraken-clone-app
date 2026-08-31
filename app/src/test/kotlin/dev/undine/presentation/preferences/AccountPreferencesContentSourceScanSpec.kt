package dev.undine.presentation.preferences

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private const val ACCOUNT_CONTENT_SOURCE_PATH =
    "src/main/kotlin/dev/undine/presentation/preferences/AccountPreferencesContent.kt"

/**
 * Compose UI 런타임을 추가하지 않고, 계정 탭의 조립 경로가 문자열 리소스와 키보드 조작을 계속
 * 소비하는지 확인한다. 상태 홀더 검증만으로는 이 연결이 빠져도 발견할 수 없다.
 */
class AccountPreferencesContentSourceScanSpec : FunSpec({

    val source = File(ACCOUNT_CONTENT_SOURCE_PATH).readText()
    val keyboardButtonCalls = source.split("AccountKeyboardActionButton(").drop(1)
        .map { call -> call.substringBefore("AccountKeyboardActionButton(") }

    test("주요 계정 조작 태그는 Enter·Space 키보드 버튼을 통해 연결된다") {
        listOf(
            "AccountPreferencesTags.PROFILE_ADD",
            "AccountPreferencesTags.PROFILE_EDIT",
            "AccountPreferencesTags.PROFILE_DELETE",
            "AccountPreferencesTags.DELETE_CONFIRM",
            "AccountPreferencesTags.MAPPING_ASSIGN",
            "AccountPreferencesTags.MAPPING_CLEAR",
            "AccountPreferencesTags.EDITOR_SUBMIT",
        ).forEach { tag ->
            keyboardButtonCalls.any { call -> call.contains(tag) } shouldBe true
        }

        source.contains("AccountKeyboardActionButton(") shouldBe true
        source.contains("Key.Enter") shouldBe true
        source.contains("Key.Spacebar") shouldBe true
    }

    test("계정 탭은 필요한 PreferencesStrings를 렌더링 경로에서 소비한다") {
        listOf(
            "texts.identityProfiles",
            "texts.identityProfilesEmpty",
            "texts.profileAdd",
            "texts.profileEdit",
            "texts.profileDelete",
            "texts.profileDeleteConfirm",
            "texts.repositoryMapping",
            "texts.repositoryMappingUnset",
            "texts.signingKey",
            "texts.signingKeyUnset",
            "texts.loadFailed",
            "texts.saveFailed",
        ).forEach { keyAccess -> source.contains(keyAccess) shouldBe true }
    }
})
