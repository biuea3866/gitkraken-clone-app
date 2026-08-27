package dev.undine.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val WINDOW = WindowBounds(width = 1280, height = 800, maximized = false)

private fun settingsWith(
    defaultBranchName: String = Settings.DEFAULT_BRANCH_NAME,
    automaticFetch: AutomaticFetchSettings = AutomaticFetchSettings.DEFAULT,
    tabWidth: Int = Settings.DEFAULT_TAB_WIDTH,
    largeFileThresholdBytes: Long = Settings.DEFAULT_LARGE_FILE_THRESHOLD_BYTES,
    commitPageSize: Int = Settings.DEFAULT_COMMIT_PAGE_SIZE,
): Settings = Settings(
    recentRepositories = emptyList(),
    theme = ThemeMode.SYSTEM,
    window = WINDOW,
    defaultBranchName = defaultBranchName,
    automaticFetch = automaticFetch,
    tabWidth = tabWidth,
    largeFileThresholdBytes = largeFileThresholdBytes,
    commitPageSize = commitPageSize,
)

/**
 * UND-74 가 탭 6건을 위해 넓힌 저장 계약 — 기본값과 **범위 거부**만 본다.
 *
 * 거부는 domain 이 한다 (`require`). 여섯 탭이 각자 범위를 알면 한 곳만 틀려도 조용히 통과하고,
 * 저장 자체가 일어나지 않아야 화면에 저장 안 된 값이 남지 않는다.
 * 영속화 왕복은 `SettingsTabValueCodecSpec` 이 실제 파일로 검증한다.
 */
class SettingsTabValueSpec : FunSpec({

    test("탭 값을 주지 않은 Settings 는 git 관례를 따른 기본값을 갖는다") {
        val settings = Settings(recentRepositories = emptyList(), theme = ThemeMode.SYSTEM, window = WINDOW)

        settings.defaultBranchName shouldBe "main"
        settings.pullStrategy shouldBe PullStrategy.MERGE
        settings.automaticFetch.enabled shouldBe false
        settings.tabWidth shouldBe 4
        // 서체 미지정은 시스템 기본을 따른다는 뜻이다 — 빈 문자열과 뭉개지 않는다.
        settings.monospaceFontFamily.shouldBeNull()
        settings.largeFileThresholdBytes shouldBe 1024L * 1024
        settings.commitPageSize shouldBe 100
    }

    test("자동 fetch 기본값은 꺼짐이고 주기는 켤 때 쓸 값으로 남아 있다") {
        AutomaticFetchSettings.DEFAULT.enabled shouldBe false
        (AutomaticFetchSettings.DEFAULT.intervalMinutes > 0) shouldBe true
    }

    test("merge 와 rebase 두 pull 방식만 존재한다 — 그 밖의 값은 표현할 수 없다") {
        PullStrategy.entries.map(PullStrategy::name) shouldBe listOf("MERGE", "REBASE")
    }

    test("빈 기본 브랜치명은 거부된다") {
        shouldThrow<IllegalArgumentException> { settingsWith(defaultBranchName = "") }
    }

    test("공백뿐인 기본 브랜치명도 거부된다 — git 이 만들 수 없는 이름이다") {
        shouldThrow<IllegalArgumentException> { settingsWith(defaultBranchName = "   ") }
    }

    test("주기가 0 이하면 거부된다 — fetch 를 켰든 껐든 같다") {
        listOf(true, false).forEach { enabled ->
            listOf(0, -1).forEach { minutes ->
                shouldThrow<IllegalArgumentException> {
                    settingsWith(automaticFetch = AutomaticFetchSettings(enabled = enabled, intervalMinutes = minutes))
                }
            }
        }
    }

    test("0 이하의 탭 폭·대용량 파일 임계치·커밋 페이지 크기는 거부된다") {
        listOf(0, -4).forEach { invalid ->
            shouldThrow<IllegalArgumentException> { settingsWith(tabWidth = invalid) }
            shouldThrow<IllegalArgumentException> { settingsWith(commitPageSize = invalid) }
            shouldThrow<IllegalArgumentException> { settingsWith(largeFileThresholdBytes = invalid.toLong()) }
        }
    }

    test("경계값 1 은 허용된다 — 거부하는 것은 0 이하뿐이다") {
        val settings = settingsWith(tabWidth = 1, largeFileThresholdBytes = 1, commitPageSize = 1)

        settings.tabWidth shouldBe 1
        settings.largeFileThresholdBytes shouldBe 1L
        settings.commitPageSize shouldBe 1
    }

    test("상한은 두지 않는다 — 큰 값이 필요한 저장소가 실제로 있다") {
        val huge = settingsWith(
            tabWidth = Int.MAX_VALUE,
            largeFileThresholdBytes = Long.MAX_VALUE,
            commitPageSize = Int.MAX_VALUE,
        )

        huge.largeFileThresholdBytes shouldBe Long.MAX_VALUE
        huge.commitPageSize shouldBe Int.MAX_VALUE
    }

    test("fetch 를 꺼도 이전 주기 값은 보존된다 — 껐다 켤 때 되찾을 값이다") {
        val scheduled = AutomaticFetchSettings(enabled = true, intervalMinutes = 30)

        val stopped = scheduled.copy(enabled = false)

        stopped.enabled shouldBe false
        stopped.intervalMinutes shouldBe 30
    }

    test("꺼진 fetch 의 주기도 양수여야 한다 — 0 이면 다시 켤 때 되찾을 값이 없다") {
        shouldThrow<IllegalArgumentException> {
            AutomaticFetchSettings(enabled = false, intervalMinutes = 0)
        }
    }
})
