package dev.undine.domain

/**
 * 영속화되는 앱 설정. 스키마 확장은 `SettingsGateway` 소유 티켓이 한다.
 *
 * [identityProfiles]·[externalTools] 는 **후행 기능(UND-37·UND-39)이 소비할 계약**이라
 * 이 두 필드에 기본값이 있다 — 값을 주지 않은 기존 호출부와 이 키가 없는 기존 설정 파일이
 * 그대로 동작해야 하기 때문이다. 소비 기능은 이 티켓 범위 밖이다.
 */
data class Settings(
    val recentRepositories: List<RepositoryPath>,
    val theme: ThemeMode,
    val window: WindowBounds,
    val identityProfiles: List<IdentityProfile> = emptyList(),
    val externalTools: ExternalToolSettings = ExternalToolSettings.NONE,
)

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

/** 마지막 창 상태. [maximized] 면 크기 값은 복원 시 무시된다. */
data class WindowBounds(
    val width: Int,
    val height: Int,
    val maximized: Boolean,
)

/**
 * 저장소마다 바꿔 쓰는 작성자 신원 한 벌.
 *
 * **자격증명을 담지 않는다** — [signingKeyId] 는 키를 가리키는 식별자일 뿐이고,
 * 키 본문·패스프레이즈는 OS 키체인과 gpg/ssh agent 소관이다. 설정 파일은 평문이다.
 */
data class IdentityProfile(
    val name: String,
    val email: String,
    /** 서명 키 **식별자**. 서명을 쓰지 않는 프로필은 없다. */
    val signingKeyId: String?,
    val defaultAuthentication: AuthenticationMethod,
    /**
     * 이 프로필을 쓰기로 한 원격 호스트. 후행 티켓(UND-37)이 실제 원격과 비교해 불일치를 경고한다.
     *
     * **없을 수 있다** — 호스트를 적지 않은 프로필은 경고 대상이 아니다(경고 없음이지 실패가 아니다).
     * 이 키가 없는 기존 프로필 파일도 그대로 읽혀야 하므로 판정은 비교가 아니라 부재로 갈린다.
     */
    val expectedHost: String?,
)

/**
 * 원격에 붙을 때 기본으로 시도할 인증 방식.
 *
 * 이 티켓은 **저장만** 한다 — 실제 원격 인증 연결은 후행 티켓이 한다.
 */
enum class AuthenticationMethod {
    SSH,
    HTTPS,
}

/**
 * 외부 diff/merge 도구의 **앱 설정 차선값**.
 *
 * Git 의 `diff.tool`·`merge.tool` 이 우선이며, 그 설정이 없을 때만 소비 티켓(UND-39)이 이 값을 쓴다.
 * 도구가 설정되지 않은 것은 실패가 아니라 정상 상태라 [NONE] 으로 표현한다.
 */
data class ExternalToolSettings(
    val diffTool: ExternalTool?,
    val mergeTool: ExternalTool?,
) {

    companion object {

        /** 두 도구 모두 설정되지 않은 상태. */
        val NONE = ExternalToolSettings(diffTool = null, mergeTool = null)
    }
}

/**
 * 실행 파일과 인자 템플릿 한 벌.
 *
 * [arguments] 는 셸을 거치지 않고 그대로 넘길 **인자 배열**이다 — 한 문자열로 합쳐 두면
 * 공백이 든 경로를 나눌 때 실행 방식이 갈린다. 자리표시자 해석은 소비 티켓이 한다.
 */
data class ExternalTool(
    val executable: String,
    val arguments: List<String>,
)
