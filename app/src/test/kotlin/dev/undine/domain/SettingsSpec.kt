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
})
