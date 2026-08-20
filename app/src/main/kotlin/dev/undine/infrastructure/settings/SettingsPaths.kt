package dev.undine.infrastructure.settings

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * 설정 파일의 OS 표준 위치를 정한다.
 *
 * macOS `~/Library/Application Support`, Linux `$XDG_CONFIG_HOME` (없으면 `~/.config`),
 * Windows `%APPDATA%` (없으면 `~/AppData/Roaming`) 아래의 `undine/settings.json` 이다.
 * OS 이름·환경변수를 인자로 받는 [settingsFileIn] 을 두어 테스트가 플랫폼을 바꿔 검증할 수 있게 한다.
 */
internal object SettingsPaths {

    private const val APPLICATION_DIRECTORY = "undine"
    private const val SETTINGS_FILE = "settings.json"

    fun currentPlatformSettingsFile(): Path = settingsFileIn(
        osName = System.getProperty("os.name").orEmpty(),
        userHome = System.getProperty("user.home").orEmpty(),
        environment = System::getenv,
    )

    fun settingsFileIn(osName: String, userHome: String, environment: (String) -> String?): Path =
        configurationRoot(osName, userHome, environment)
            .resolve(APPLICATION_DIRECTORY)
            .resolve(SETTINGS_FILE)

    private fun configurationRoot(
        osName: String,
        userHome: String,
        environment: (String) -> String?,
    ): Path = when {
        osName.startsWith("mac", ignoreCase = true) ->
            Path(userHome, "Library", "Application Support")

        osName.startsWith("windows", ignoreCase = true) ->
            environment("APPDATA").asPathOrNull() ?: Path(userHome, "AppData", "Roaming")

        else ->
            environment("XDG_CONFIG_HOME").asPathOrNull() ?: Path(userHome, ".config")
    }

    private fun String?.asPathOrNull(): Path? = takeUnless { it.isNullOrBlank() }?.let { Path(it) }
}
