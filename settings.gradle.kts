rootProject.name = "undine"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// 단일 서브프로젝트 — 소스는 루트가 아니라 app/ 아래에 둔다.
include(":app")
