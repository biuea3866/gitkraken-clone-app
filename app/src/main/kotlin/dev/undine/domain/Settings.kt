package dev.undine.domain

/** 영속화되는 앱 설정. 스키마 확장은 `SettingsGateway` 소유 티켓이 한다. */
data class Settings(
    val recentRepositories: List<RepositoryPath>,
    val theme: ThemeMode,
    val window: WindowBounds,
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
