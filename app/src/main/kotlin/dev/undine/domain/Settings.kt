package dev.undine.domain

/**
 * 영속화되는 앱 설정. 스키마 확장은 `SettingsGateway` 소유 티켓이 한다.
 *
 * [identityProfiles]·[externalTools] 는 **후행 기능(UND-37·UND-39)이 소비할 계약**이라
 * 이 두 필드에 기본값이 있다 — 값을 주지 않은 기존 호출부와 이 키가 없는 기존 설정 파일이
 * 그대로 동작해야 하기 때문이다. 소비 기능은 이 티켓 범위 밖이다.
 *
 * [language] 이후 여섯 필드는 UND-63 이 wave 8 소비자 셋(UND-40 환경설정·UND-44 탭·UND-48 업데이트)을
 * 위해 한 번에 넓힌 자리다. 같은 이유로 **전부 기본값 있는 선택 필드**다 — 이 키가 없는 기존 설정
 * 파일을 그대로 읽어야 한다. **값의 의미를 해석하는 것은 소비 티켓**이고, 여기와 `SettingsCodec` 은
 * 담는 자리와 왕복만 책임진다.
 *
 * @property language 화면에 쓸 언어의 IETF BCP 47 태그(`Locale.toLanguageTag()`).
 * `null` 은 **시스템 로케일을 따른다**는 뜻이다 — 빈 문자열과 뭉개지 않는다.
 * @property reopenLastRepository 시작할 때 마지막 저장소를 여는지. 끄면 기존 동작(환영 화면)이다.
 * @property confirmDestructiveActions 파괴적 연산 확인 대화상자를 띄우는지. **다이얼로그별 스위치가
 * 아니라 전반에 걸리는 단일 스위치**다 — 어느 연산이 파괴적인지의 판단은 소비 티켓이 한다.
 * @property openTabs 열려 있는 저장소 탭. [recentRepositories] 와 같은 표현을 쓰되 **중복 제거·상한
 * 절단을 하지 않는다** — 같은 저장소를 두 탭으로 여는 것은 사용자의 선택이고, 경로가 사라진 탭도
 * 조용히 버리지 않는다(UND-44 요구).
 * @property activeTabIndex [openTabs] 안의 활성 탭 위치. 범위를 벗어난 값은 읽는 쪽이 0 으로 클램프한다.
 * @property updateCheck 자동 업데이트 확인 주기와 on/off.
 */
data class Settings(
    val recentRepositories: List<RepositoryPath>,
    val theme: ThemeMode,
    val window: WindowBounds,
    val identityProfiles: List<IdentityProfile> = emptyList(),
    val externalTools: ExternalToolSettings = ExternalToolSettings.NONE,
    val language: String? = null,
    val reopenLastRepository: Boolean = false,
    val confirmDestructiveActions: Boolean = true,
    val openTabs: List<RepositoryPath> = emptyList(),
    val activeTabIndex: Int = 0,
    val updateCheck: UpdateCheckSettings = UpdateCheckSettings.DEFAULT,
)

/**
 * 자동 업데이트 확인 주기 설정.
 *
 * 평평한 키 두 개로 흩지 않고 [WindowBounds]·[ExternalToolSettings] 처럼 한 단위로 묶는다 —
 * 두 값은 항상 함께 읽히고 함께 바뀐다.
 *
 * 실제 확인·다운로드는 소비 티켓(UND-48) 소관이다. 여기는 값만 보관한다.
 */
data class UpdateCheckSettings(
    val enabled: Boolean,
    val intervalHours: Int,
) {

    companion object {

        /** 켜짐 · 하루 한 번. 확인을 끄는 것은 사용자의 명시적 선택이다. */
        val DEFAULT = UpdateCheckSettings(enabled = true, intervalHours = 24)

        /**
         * 뜻이 있는 주기 범위 — 한 시간에 한 번부터 이레에 한 번까지.
         *
         * 더 잦으면 릴리즈 서버를 의미 없이 두드리고, 더 뜸하면 확인을 끈 것과 다르지 않다.
         * 범위 밖의 저장 값은 오류가 아니라 [DEFAULT] 의 주기로 읽는다.
         */
        val INTERVAL_HOURS_RANGE = 1..168
    }
}

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
