package dev.undine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

/**
 * 빌드가 심은 버전이 `gradle.properties` 값과 같은지 본다.
 *
 * 버전이 두 곳에 적히면 릴리즈마다 한쪽을 잊고, 그러면 **앱이 거짓 버전을 보여준다.** 테스트는
 * 빌드가 넘긴 시스템 프로퍼티(`undine.version`)와 생성된 상수를 대조해 그 어긋남을 잡는다.
 */
class BuildInfoSpec : FunSpec({

    test("생성된 버전이 gradle.properties 값과 일치한다") {
        val expected = requireNotNull(System.getProperty("undine.version")) {
            "빌드가 undine.version 을 테스트에 넘기지 않았다 (app/build.gradle.kts tasks.test)"
        }

        BuildInfo.VERSION shouldBe expected
    }

    test("버전이 패키징이 받아들이는 형식이다") {
        // macOS jpackage 는 major 가 1 이상인 MAJOR.MINOR.PATCH 만 받는다 — 0.x 는 dmg 생성에서 막힌다.
        BuildInfo.VERSION shouldMatch Regex("""[1-9]\d*\.\d+\.\d+""")
    }
})
