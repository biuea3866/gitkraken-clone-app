package dev.undine.infrastructure.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path

private const val USER_HOME = "/home/tester"

private fun environmentOf(vararg entries: Pair<String, String>): (String) -> String? =
    entries.toMap()::get

/** 플랫폼별 설정 루트 선택은 이 티켓 소유 로직이라 OS 이름·환경변수를 주입해 검증한다. */
class SettingsPathsSpec : FunSpec({

    test("macOS 는 Library/Application Support 아래에 저장한다") {
        SettingsPaths.settingsFileIn(
            osName = "Mac OS X",
            userHome = USER_HOME,
            environment = environmentOf(),
        ) shouldBe Path("$USER_HOME/Library/Application Support/undine/settings.json")
    }

    test("Linux 는 XDG_CONFIG_HOME 이 있으면 그 아래에 저장한다") {
        SettingsPaths.settingsFileIn(
            osName = "Linux",
            userHome = USER_HOME,
            environment = environmentOf("XDG_CONFIG_HOME" to "/custom/config"),
        ) shouldBe Path("/custom/config/undine/settings.json")
    }

    test("Linux 는 XDG_CONFIG_HOME 이 비어 있으면 ~/.config 로 떨어진다") {
        SettingsPaths.settingsFileIn(
            osName = "Linux",
            userHome = USER_HOME,
            environment = environmentOf("XDG_CONFIG_HOME" to "  "),
        ) shouldBe Path("$USER_HOME/.config/undine/settings.json")
    }

    test("Windows 는 APPDATA 아래에 저장한다") {
        SettingsPaths.settingsFileIn(
            osName = "Windows 11",
            userHome = USER_HOME,
            environment = environmentOf("APPDATA" to "/users/tester/AppData/Roaming"),
        ) shouldBe Path("/users/tester/AppData/Roaming/undine/settings.json")
    }

    test("Windows 는 APPDATA 가 없으면 홈의 AppData/Roaming 으로 떨어진다") {
        SettingsPaths.settingsFileIn(
            osName = "Windows 10",
            userHome = USER_HOME,
            environment = environmentOf(),
        ) shouldBe Path("$USER_HOME/AppData/Roaming/undine/settings.json")
    }

    test("현재 플랫폼 경로는 undine/settings.json 으로 끝난다") {
        val settingsFile = SettingsPaths.currentPlatformSettingsFile()

        settingsFile.fileName.toString() shouldBe "settings.json"
        settingsFile.parent.fileName.toString() shouldBe "undine"
        settingsFile.isAbsolute shouldBe true
    }
})
