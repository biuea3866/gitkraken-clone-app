// 루트는 플러그인 버전 선언만 한다 — 적용은 app/build.gradle.kts 가 한다.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.detekt) apply false
}
