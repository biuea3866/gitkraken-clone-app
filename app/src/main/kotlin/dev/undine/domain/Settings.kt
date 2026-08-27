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
 * @property shortcutOverrides **커맨드 id → 사용자가 바꾼 단축키**. 오버라이드가 없는 커맨드는 항목을
 * 만들지 않는다 — 없다는 것이 곧 "기본 단축키를 쓴다" 는 뜻이라, 기본값을 적어 두면 나중에 기본값이
 * 바뀌어도 옛 값이 남는다. 지금 등록돼 있지 않은 커맨드의 항목도 이 자리에서는 지우지 않는다
 * (그 정리 규칙은 단축키 탭이 정한다).
 *
 * [defaultBranchName] 이후 일곱 필드는 UND-74 가 탭 6건(Git·도구·고급)을 위해 넓힌 자리다. 앞의
 * 필드들과 같은 이유로 전부 기본값 있는 선택 필드이며, **값을 소비하는 경로**(diff 접기·이력 페이지
 * 크기·fetch 스케줄러)는 별도 후속 티켓이다 — 여기와 `SettingsCodec` 은 담는 자리와 왕복만 책임진다.
 *
 * **범위 위반은 여기서 거부한다.** 여섯 탭이 각자 범위를 알면 한 곳만 틀려도 조용히 통과한다.
 * 생성이 실패하면 저장 자체가 일어나지 않아 화면에 저장 안 된 값이 남지 않는다. 거부하는 것은
 * **명백히 틀린 값**(빈 이름·0 이하)뿐이고 **상한은 두지 않는다** — 근거 없는 상한은 큰 값이
 * 필요한 저장소를 막는다.
 *
 * @property defaultBranchName 새 저장소를 만들 때 쓸 기본 브랜치 이름.
 * @property pullStrategy pull 이 원격 변경을 합치는 방식.
 * @property automaticFetch 자동 fetch 의 on/off 와 주기.
 * @property tabWidth 탭 문자를 몇 칸으로 보일지.
 * @property monospaceFontFamily 고정폭 서체 이름. `null` 은 **시스템 기본을 따른다**는 뜻이다 —
 * [language] 와 같은 규약이라 빈 문자열과 뭉개지 않는다.
 * @property largeFileThresholdBytes 이보다 큰 파일을 "대용량" 으로 다루는 경계(바이트).
 * @property commitPageSize 이력을 한 번에 몇 개씩 읽을지.
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
    val shortcutOverrides: Map<String, ShortcutBinding> = emptyMap(),
    val defaultBranchName: String = DEFAULT_BRANCH_NAME,
    val pullStrategy: PullStrategy = DEFAULT_PULL_STRATEGY,
    val automaticFetch: AutomaticFetchSettings = AutomaticFetchSettings.DEFAULT,
    val tabWidth: Int = DEFAULT_TAB_WIDTH,
    val monospaceFontFamily: String? = null,
    val largeFileThresholdBytes: Long = DEFAULT_LARGE_FILE_THRESHOLD_BYTES,
    val commitPageSize: Int = DEFAULT_COMMIT_PAGE_SIZE,
) {

    init {
        // 공백뿐인 이름도 거부한다 — git 이 만들 수 없는 이름이라 빈 문자열과 똑같이 명백히 틀렸다.
        require(defaultBranchName.isNotBlank()) { "기본 브랜치 이름이 비어 있습니다" }
        require(tabWidth > 0) { "탭 폭은 1 이상이어야 합니다: $tabWidth" }
        require(largeFileThresholdBytes > 0) {
            "대용량 파일 임계치는 1 바이트 이상이어야 합니다: $largeFileThresholdBytes"
        }
        require(commitPageSize > 0) { "커밋 페이지 크기는 1 이상이어야 합니다: $commitPageSize" }
    }

    companion object {

        /** 테마 기본값 — OS 설정을 따른다. 항목별 기본값 복원과 최초 실행이 같은 값을 쓴다. */
        val DEFAULT_THEME: ThemeMode = ThemeMode.SYSTEM

        /** git 이 새 저장소에 쓰는 이름과 같은 값. 앱이 다른 관례를 만들지 않는다. */
        const val DEFAULT_BRANCH_NAME: String = "main"

        /** git 의 pull 기본 동작과 같다 — rebase 는 사용자가 명시적으로 고르는 쪽이다. */
        val DEFAULT_PULL_STRATEGY: PullStrategy = PullStrategy.MERGE

        /** 탭 폭 기본값. git 의 `core.pager` 관례와 같은 4 칸이다. */
        const val DEFAULT_TAB_WIDTH: Int = 4

        /** 대용량 파일 경계 기본값 — 1 MiB. */
        const val DEFAULT_LARGE_FILE_THRESHOLD_BYTES: Long = 1024L * 1024

        /** 이력을 한 번에 읽는 개수 기본값. */
        const val DEFAULT_COMMIT_PAGE_SIZE: Int = 100

        /** 창 크기 기본값. 저장된 창 상태가 없을 때 이 값으로 연다. */
        val DEFAULT_WINDOW: WindowBounds = WindowBounds(width = 1280, height = 800, maximized = false)

        /**
         * 저장된 설정이 없거나 읽을 수 없을 때 시작하는 값.
         *
         * infrastructure 의 기본값도 이 값을 쓴다 — 두 곳에 적으면 "기본값" 이 무엇인지가 갈린다.
         */
        val DEFAULTS: Settings = Settings(
            recentRepositories = emptyList(),
            theme = DEFAULT_THEME,
            window = DEFAULT_WINDOW,
        )
    }
}

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

/**
 * pull 이 원격 변경을 합치는 방식.
 *
 * **enum 이라 그 밖의 값은 애초에 표현되지 않는다** — 문자열로 두고 `require` 로 거르면 잘못된
 * 값이 타입 안에 잠깐이라도 존재하고, 그 검사를 빠뜨린 경로가 생긴다. 설정 파일에서 온 알 수 없는
 * 문자열은 `SettingsCodec` 이 기본값으로 읽는다(테마와 같은 규약).
 */
enum class PullStrategy {
    MERGE,
    REBASE,
}

/**
 * 자동 fetch 의 on/off 와 주기.
 *
 * [UpdateCheckSettings] 와 같은 이유로 한 단위다 — 두 값은 항상 함께 읽히고 함께 바뀐다.
 *
 * **꺼짐을 주기 0 으로 표현하지 않는다.** 0 을 꺼짐으로 쓰면 "0분마다" 와 구분되지 않고, 껐다 켤 때
 * 이전 주기를 잃는다.
 *
 * 그래서 [intervalMinutes] 는 **[enabled] 와 무관하게 항상 양수**다. 꺼진 상태의 주기를 검증하지
 * 않으면 `(enabled=false, intervalMinutes=0)` 이 만들어지는데, 그 값은 다시 켤 때 되찾을 것이
 * 없어 "껐다 켤 때 이전 주기를 잃지 않는다" 는 목적을 배신한다. 앱이 뜻 없는 주기를 만들 수 없으면
 * `SettingsCodec` 이 읽으면서 값을 되돌릴 일도 없다.
 *
 * 실제 fetch 스케줄링은 소비 티켓 소관이다. 여기는 값만 보관한다.
 */
data class AutomaticFetchSettings(
    val enabled: Boolean,
    val intervalMinutes: Int,
) {

    init {
        require(intervalMinutes > 0) {
            "자동 fetch 주기는 1분 이상이어야 합니다: $intervalMinutes"
        }
    }

    companion object {

        /**
         * 꺼짐 · 10분. 자동 fetch 는 원격을 주기적으로 두드리므로 켜는 것이 사용자의 명시적 선택이다.
         * 꺼져 있어도 주기 값을 들고 있어야 켤 때 되찾을 값이 있다.
         */
        val DEFAULT = AutomaticFetchSettings(enabled = false, intervalMinutes = 10)
    }
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
