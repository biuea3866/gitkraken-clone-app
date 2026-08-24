package dev.undine.presentation.i18n

import androidx.compose.runtime.CompositionLocalProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.Locale

private const val APP_SOURCE_PATH = "src/main/kotlin/dev/undine/presentation/App.kt"

/**
 * CompositionLocal 정의와 기본 제공값. 컴포지션을 직접 돌려 **Composable 읽기 경로**를 검증한다 —
 * 실제 화면 트리 렌더링 검증은 UND-26 이후다.
 */
class LocalStringsSpec : FunSpec({

    test("CompositionLocal 기본 제공값은 시스템 로케일 문자열이다") {
        withDefaultLocale(Locale.US) { systemStrings().locale } shouldBe Locale.ENGLISH
        withDefaultLocale(Locale.KOREA) { systemStrings().locale } shouldBe Locale.KOREAN
        withDefaultLocale(Locale.FRANCE) { systemStrings().locale } shouldBe Locale.KOREAN
    }

    test("Provider 로 주입한 로케일의 문자열을 Composable 이 읽는다") {
        val english = builtInStringCatalog().stringsFor(Locale.ENGLISH, devBuild = false)
        val korean = builtInStringCatalog().stringsFor(Locale.KOREAN, devBuild = false)

        composeCapturing { capture ->
            CompositionLocalProvider(LocalStrings provides english) { capture(strings.common.ok) }
        } shouldBe "OK"

        composeCapturing { capture ->
            CompositionLocalProvider(LocalStrings provides korean) { capture(strings.common.ok) }
        } shouldBe "확인"
    }

    // 기본 제공값은 첫 접근에 한 번만 계산돼 캐시되므로 로케일을 바꿔가며 반복 검증할 수 없다.
    test("제공자가 없으면 Composable 이 시스템 로케일 기본 제공값을 읽는다") {
        composeCapturing { capture -> capture(strings.common.ok) } shouldBe systemStrings().common.ok
    }

    // UND-26 이 배선을 했으므로 "배선하지 않았음" 가드를 뒤집는다 — 배선이 사라지면 화면 문자열이
    // 시스템 로케일 기본값으로 조용히 되돌아가고, 그 회귀는 눈으로 잡기 어렵다.
    test("App.kt 가 화면 트리에 i18n 을 배선한다") {
        val appSource = File(APP_SOURCE_PATH)

        appSource.isFile shouldBe true
        appSource.readText() shouldContain "LocalStrings provides"
    }
})
