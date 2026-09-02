package dev.undine.scenario2

import dev.undine.domain.externaltool.DiffToolInput
import dev.undine.domain.externaltool.ExternalToolUnavailable
import dev.undine.domain.signing.SignResult
import dev.undine.domain.signing.SigningFormat
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.StoredConfig
import java.io.File

/** 어떤 환경에도 설치돼 있지 않은 이름. PATH 를 뒤져도 나오지 않아야 판정이 결정적이다. */
private const val MISSING_EXECUTABLE = "undine-존재하지-않는-도구"
private const val TOOL_NAME = "없는도구"

/**
 * 2차 시나리오 10(축소) — 외부 도구·서명이 **없는 환경**에서 각 경로가 무엇을 돌려주는지 확인한다.
 *
 * 설치를 전제하지 않는다. 도구가 있어야 통과하는 테스트는 개발자 머신에서만 통과하고 CI 에서 깨진다 —
 * 그래서 검증 대상은 "미설치일 때의 결과" 다.
 *
 * **이 시나리오는 새 안내 문구 계약을 만들지 않는다.** 외부 도구는
 * `ProcessExternalToolRunner.isInstalled` 의 기존 미설치 판정을, 서명은 `SigningGateway.sign()` 의 기존
 * `SignResult.Failed` 를 그대로 확인한다. 두 판정 모두 **실제 임시 저장소를 연 앱 경로**로만 부른다 —
 * 러너를 직접 만들어 부르면 그 판정이 앱에 이어져 있는지는 확인하지 못한다. 같은 축의
 * **LFS 미설치 안내는 대응 `domain/lfs` 와 UseCase 가 현재 소스에 없어 이 티켓 범위에서 제외**했다 —
 * 자세한 사유는 [Scenario2App] 의 설명에 있다.
 */
class MissingToolScenario2Spec : FunSpec({

    test("설정된 diff 도구가 설치돼 있지 않으면 도구를 띄우지 않고 그 실행 파일과 함께 알린다") {
        appWithConfig({ config ->
            config.setString("diff", null, "tool", TOOL_NAME)
            config.setString("difftool", TOOL_NAME, "cmd", "$MISSING_EXECUTABLE \$LOCAL \$REMOTE")
        }) { app ->
            // 설치 여부 판정도 앱 경로로 묻는다 — `ProcessExternalToolRunner.isInstalled` 가 그 뒤에 있다.
            app.checkToolAvailability.execute(MISSING_EXECUTABLE) shouldBe false

            val result = app.openDiffTool.execute(DiffToolInput(local = "왼쪽\n", remote = "오른쪽\n"))

            // 프로세스를 띄우기 전에 미설치로 끝난다 — 실행됐다면 결과가 종료 코드였을 것이다.
            result.shouldBeInstanceOf<ExternalToolUnavailable.ToolNotFound>().executable shouldBe MISSING_EXECUTABLE
        }
    }

    test("도구가 아예 설정돼 있지 않으면 미설정으로 알린다") {
        appWithConfig({ }) { app ->
            app.openDiffTool.execute(DiffToolInput(local = "왼쪽\n", remote = "오른쪽\n")) shouldBe
                ExternalToolUnavailable.NoToolConfigured
        }
    }

    test("서명할 키가 설정돼 있지 않으면 서명하지 않고 사유와 함께 실패를 돌려준다") {
        appWithConfig({ config -> config.setString("gpg", null, "format", "ssh") }) { app ->
            app.signing.settings().format shouldBe SigningFormat.SSH
            app.signing.settings().signingKey shouldBe null

            val signed = app.signing.sign("서명 대상 바이트".toByteArray())

            // 실패는 예외가 아니라 결과다 — 호출부가 "서명 실패면 커밋도 만들지 않는다" 를 지킬 수 있어야 한다.
            signed.shouldBeInstanceOf<SignResult.Failed>().reason shouldBe SignResult.Failed.Reason.NO_SIGNING_KEY
        }
    }

    test("서명 agent 가 없으면 서명을 만들지 못하고 실패로 돌려준다") {
        val missingKey = File(tempdir(), "없는-서명-키")
        appWithConfig({ config ->
            config.setString("gpg", null, "format", "ssh")
            config.setString("user", null, "signingkey", missingKey.absolutePath)
        }) { app ->
            val signed = app.signing.sign("서명 대상 바이트".toByteArray())

            // agent 가 없는 것과 프로그램이 없는 것 모두 사용자에게는 "서명되지 않았다" 다 —
            // 어느 쪽이든 서명이 만들어져서는 안 된다.
            signed.shouldBeInstanceOf<SignResult.Failed>()
        }
    }
})

/**
 * 저장소 설정을 **연 뒤가 아니라 열기 전에** 심는다. 열린 뒤에 파일을 고치면 JGit 이 갱신을 알아채는
 * 시점이 파일시스템 타임스탬프 해상도에 걸려 결과가 흔들린다.
 *
 * 세션 종료는 [use] 가 보장한다 — 단정이 실패해도 저장소 핸들이 남지 않는다.
 */
private suspend fun TestConfiguration.appWithConfig(
    configure: (StoredConfig) -> Unit,
    block: suspend (Scenario2App) -> Unit,
) {
    val work = seedRepository(tempdir())
    Git.open(work).use { git ->
        git.repository.config.also(configure).save()
    }
    scenario2AppAt(work).use { app ->
        app.open()
        block(app)
    }
}
