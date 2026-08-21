plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.detekt)
}

kotlin {
    // JDK 버전은 gradle.properties 의 undine.jvm 이 SSOT — 여기에 숫자를 쓰지 않는다.
    jvmToolchain(providers.gradleProperty("undine.jvm").get().toInt())
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.jgit)
    implementation(libs.coroutines.core)
    implementation(libs.jgit.ssh.apache)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    // Compose 화면 렌더링 테스트(testing.md). 버전은 compose 플러그인이 정하므로 카탈로그 항목이 없다.
    // 아티팩트 이름에 JUnit4 가 들어가지만 테스트는 Kotest 의 runComposeUiTest 로 구동한다 —
    // 테스트 코드에 org.junit.* 를 import 하지 않는다.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "dev.undine.presentation.AppKt"
        // 배포 포맷(nativeDistributions) 설정은 UND-25 소관이다.
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// Kotest 는 JUnit Platform 위에서 실행된다 — 테스트 코드에 org.junit.* 를 쓰는 것과는 다른 축이다.
tasks.test {
    useJUnitPlatform()
}
