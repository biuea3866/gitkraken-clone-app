package dev.undine.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val WINDOW = WindowBounds(width = 1280, height = 800, maximized = false)

/**
 * UND-59 가 넓힌 공통 계약의 값 보관만 본다 — 소비(UND-37·39)는 이 티켓 범위 밖이다.
 * 영속화 round-trip 은 `SettingsGatewayImplSpec` 이 실제 파일로 검증한다.
 */
class SettingsSpec : FunSpec({

    test("identity 프로필은 이름·이메일·서명 키 ID·기본 인증 방식·예상 호스트를 한 단위로 보관한다") {
        val profile = IdentityProfile(
            name = "일 이름",
            email = "work@example.com",
            signingKeyId = "ABCD1234",
            defaultAuthentication = AuthenticationMethod.SSH,
            expectedHost = "github.com",
        )

        profile.name shouldBe "일 이름"
        profile.email shouldBe "work@example.com"
        profile.signingKeyId shouldBe "ABCD1234"
        profile.defaultAuthentication shouldBe AuthenticationMethod.SSH
        profile.expectedHost shouldBe "github.com"
    }

    test("서명 키가 없는 프로필은 키 ID 를 비워 둔다") {
        val profile = IdentityProfile(
            name = "개인",
            email = "me@example.com",
            signingKeyId = null,
            defaultAuthentication = AuthenticationMethod.HTTPS,
            expectedHost = null,
        )

        profile.signingKeyId.shouldBeNull()
    }

    test("예상 호스트를 적지 않은 프로필은 호스트를 비워 둔다 — 경고 대상이 아니다") {
        val profile = IdentityProfile(
            name = "개인",
            email = "me@example.com",
            signingKeyId = null,
            defaultAuthentication = AuthenticationMethod.HTTPS,
            expectedHost = null,
        )

        profile.expectedHost.shouldBeNull()
    }

    test("외부 도구 설정은 실행 파일과 인자 템플릿을 보관한다") {
        val tools = ExternalToolSettings(
            diffTool = ExternalTool(executable = "/usr/bin/kdiff3", arguments = listOf("\$LOCAL", "\$REMOTE")),
            mergeTool = null,
        )

        tools.diffTool?.executable shouldBe "/usr/bin/kdiff3"
        tools.diffTool?.arguments shouldBe listOf("\$LOCAL", "\$REMOTE")
        tools.mergeTool.shouldBeNull()
    }

    test("설정하지 않은 외부 도구는 두 도구 모두 비어 있다") {
        ExternalToolSettings.NONE.diffTool.shouldBeNull()
        ExternalToolSettings.NONE.mergeTool.shouldBeNull()
    }

    test("새 필드를 주지 않은 Settings 는 빈 프로필 목록과 빈 외부 도구 설정을 갖는다") {
        val settings = Settings(
            recentRepositories = emptyList(),
            theme = ThemeMode.SYSTEM,
            window = WINDOW,
        )

        settings.identityProfiles.shouldBeEmpty()
        settings.externalTools shouldBe ExternalToolSettings.NONE
    }

    test("wave 8 이 더한 필드를 주지 않은 Settings 는 결정된 기본값을 갖는다") {
        val settings = Settings(
            recentRepositories = emptyList(),
            theme = ThemeMode.SYSTEM,
            window = WINDOW,
        )

        // 언어 미지정은 시스템 로케일을 따른다는 뜻이다 — 빈 문자열과 뭉개지 않는다.
        settings.language.shouldBeNull()
        // 시작 화면은 기존 동작(환영 화면)이 기본이다.
        settings.reopenLastRepository shouldBe false
        // 파괴적 연산 확인은 켜진 상태가 기본이다 — 끄는 것은 사용자의 명시적 선택이다.
        settings.confirmDestructiveActions shouldBe true
        settings.openTabs.shouldBeEmpty()
        settings.activeTabIndex shouldBe 0
        settings.updateCheck shouldBe UpdateCheckSettings.DEFAULT
    }

    test("업데이트 확인 기본값은 켜짐 · 24시간이다") {
        UpdateCheckSettings.DEFAULT.enabled shouldBe true
        UpdateCheckSettings.DEFAULT.intervalHours shouldBe 24
    }

    test("업데이트 확인 주기는 1시간에서 168시간(7일) 사이만 뜻이 있다") {
        UpdateCheckSettings.INTERVAL_HOURS_RANGE shouldBe 1..168
        (UpdateCheckSettings.DEFAULT.intervalHours in UpdateCheckSettings.INTERVAL_HOURS_RANGE) shouldBe true
    }

    test("탭 목록은 최근 저장소와 같은 표현을 쓴다 — 경로가 사라져도 항목이 남는다") {
        val missing = RepositoryPath("/tmp/undine-removed-repository")
        val settings = Settings(
            recentRepositories = emptyList(),
            theme = ThemeMode.SYSTEM,
            window = WINDOW,
            openTabs = listOf(missing),
            activeTabIndex = 0,
        )

        settings.openTabs shouldBe listOf(missing)
    }
})
