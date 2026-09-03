import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.detekt)
}

/** 배포 버전. gradle.properties 한 곳에서만 읽는다 — 태그·번들·앱 표시가 어긋나지 않게. */
val undineVersion: String = providers.gradleProperty("undine.version").get()

/**
 * 앱이 자기 버전을 알 수 있게 상수를 생성한다.
 *
 * 버전을 코드에 또 적으면 릴리즈마다 두 곳을 고쳐야 하고, 한쪽을 잊으면 **앱이 거짓 버전을**
 * 보여준다. 빌드가 gradle.properties 에서 읽어 심는다.
 */
val generateBuildInfo by tasks.registering {
    val version = undineVersion
    val outputDirectory = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("version", version)
    outputs.dir(outputDirectory)
    doLast {
        val target = outputDirectory.get().asFile.resolve("dev/undine/BuildInfo.kt")
        target.parentFile.mkdirs()
        target.writeText(
            """
            package dev.undine

            /** 빌드가 심는 값. 손으로 고치지 않는다 — gradle.properties 의 undine.version 이 SSOT 다. */
            object BuildInfo {
                const val VERSION: String = "$version"
            }

            """.trimIndent(),
        )
    }
}

/**
 * 패키징 자산(아이콘)이 실제로 있는지 **패키징 시작 전에** 본다.
 *
 * 없으면 jpackage 가 한참 뒤 알아보기 어려운 오류로 실패한다 — 먼저 멈추고 무엇을 실행해야
 * 하는지 알려준다. 아이콘은 `packaging/make-icons.py` 가 만든다.
 */
val verifyPackagingAssets by tasks.registering {
    val icons = listOf(
        rootProject.file("packaging/undine.icns"),
        rootProject.file("packaging/undine.ico"),
        rootProject.file("packaging/undine.png"),
    )
    doLast {
        icons.forEach { icon ->
            require(icon.isFile) {
                "패키징 아이콘이 없습니다: $icon — `python3 packaging/make-icons.py` 를 실행하세요"
            }
        }
    }
}

kotlin {
    // JDK 버전은 gradle.properties 의 undine.jvm 이 SSOT — 여기에 숫자를 쓰지 않는다.
    jvmToolchain(providers.gradleProperty("undine.jvm").get().toInt())
    sourceSets.named("main") {
        kotlin.srcDir(generateBuildInfo)
    }
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

        // 대형 저장소의 이력·diff 를 메모리에 올린다 — 기본 힙으로는 부족하다.
        jvmArgs += listOf("-Xmx2g")

        nativeDistributions {
            // 현재 OS 에 해당하는 포맷만 생성된다 (jpackage 가 크로스 빌드를 하지 않는다).
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Undine"
            packageVersion = undineVersion
            description = "Kotlin · Compose Desktop · JGit 으로 만든 Git 클라이언트"
            vendor = "Undine"
            copyright = "© Undine"

            /*
             * 런타임 모듈. 빠지면 **빌드가 아니라 실행 시점에** 실패하므로 근거를 남긴다.
             *
             * 앞의 여섯 개는 `./gradlew suggestRuntimeModules` 가 jdeps 정적 분석으로 준 목록이다.
             * jdk.crypto.ec 는 그 분석에 잡히지 않는다 — TLS ECDHE 곡선 구현이 서비스 제공자라
             * 정적 참조가 없고, 없으면 https 원격 접속이 핸드셰이크에서 실패한다.
             */
            modules(
                "java.instrument",
                "java.management",
                "java.rmi",
                "java.security.jgss",
                "java.sql",
                "jdk.unsupported",
                "jdk.crypto.ec",
            )

            macOS {
                iconFile.set(rootProject.file("packaging/undine.icns"))
                bundleID = "dev.undine.app"
            }
            windows {
                iconFile.set(rootProject.file("packaging/undine.ico"))
                menu = true
                // 업그레이드 시 같은 제품으로 인식되게 고정한다 — 바뀌면 별개 앱으로 설치된다.
                upgradeUuid = "2f7c6c1e-6d0b-4c2f-9a6b-1a4f5e2d9c31"
            }
            linux {
                iconFile.set(rootProject.file("packaging/undine.png"))
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

/**
 * 테스트 소스도 검증 경로에 넣는다.
 *
 * 타입 해석까지 도는 `detektTest` 는 기본으로 `check` 에 걸리지 않는다 — 테스트 소스가 사실상
 * **검사 밖에** 있었고, 검사를 안 도는 코드가 검사의 근거였다. 기존 위반은 전용 baseline 으로
 * 고정해 빌드를 막지 않게 하고, baseline 에 없는 **새 위반만** 실패시킨다.
 *
 * baseline 은 영구 면제가 아니라 **빚 목록**이다 — 항목 수와 줄이는 방법은
 * `config/detekt/README.md` 에 적고, `TestSourceAnalysisBaselineSpec` 이 그 숫자가 실제
 * baseline 과 어긋나지 않는지 본다. 파일이 없거나 경로가 틀리면 Gradle 이 입력 검증에서
 * 멈춘다 — 억제가 조용히 사라지는 대신 빌드가 실패한다.
 */
val testDetektBaseline = rootProject.file("config/detekt/detekt-baseline-test.xml")

tasks.named<Detekt>("detektTest") {
    baseline.set(testDetektBaseline)
}

tasks.named<DetektCreateBaselineTask>("detektBaselineTest") {
    baseline.set(testDetektBaseline)
}

tasks.named("check") { dependsOn(tasks.named<Detekt>("detektTest")) }

// Kotest 는 JUnit Platform 위에서 실행된다 — 테스트 코드에 org.junit.* 를 쓰는 것과는 다른 축이다.
// 배포 산출물을 만드는 태스크는 자산 검증을 먼저 통과해야 한다.
tasks.matching { task -> task.name.startsWith("package") || task.name == "createDistributable" }
    .configureEach { dependsOn(verifyPackagingAssets) }

tasks.test {
    useJUnitPlatform()
    // 생성된 BuildInfo 가 정말 이 값을 담았는지 테스트가 대조한다.
    systemProperty("undine.version", undineVersion)
}
