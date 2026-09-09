package dev.undine.domain.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * 릴리즈 태그 파싱과 버전 비교.
 *
 * 계약 형식이 아닌 값을 **추측해 통과시키지 않는지**가 핵심이다 — 통과시키면 존재할 수 없는
 * 릴리즈를 "새 버전" 으로 안내하거나, 엉뚱한 자산 이름을 만들어 조용히 아무것도 못 찾는다.
 */
class AppVersionSpec : FunSpec({

    test("MAJOR.MINOR.PATCH 를 읽는다") {
        AppVersion.parse("1.2.3") shouldBe AppVersion(1, 2, 3)
    }

    test("태그는 v 를 떼고 읽는다") {
        AppVersion.parseTag("v1.0.0") shouldBe AppVersion(1, 0, 0)
    }

    test("v 없는 태그는 계약 위반이라 읽지 않는다") {
        AppVersion.parseTag("1.0.0").shouldBeNull()
    }

    test("major 0 은 발행될 수 없는 태그라 거부한다") {
        // macOS jpackage 가 major 0 을 거부해 dmg 자체가 만들어지지 않는다 (RELEASE-CONTRACT).
        AppVersion.parse("0.9.9").shouldBeNull()
        AppVersion.parseTag("v0.1.0").shouldBeNull()
    }

    test("접미사·자리 수가 다른 값은 읽지 않는다") {
        AppVersion.parse("1.2.3-rc1").shouldBeNull()
        AppVersion.parse("1.2").shouldBeNull()
        AppVersion.parse("1.2.3.4").shouldBeNull()
        AppVersion.parse("").shouldBeNull()
        AppVersion.parse("정식").shouldBeNull()
    }

    test("정수 범위를 넘는 자리는 읽지 않는다") {
        AppVersion.parse("1.0.99999999999999999999").shouldBeNull()
    }

    test("버전은 major → minor → patch 순으로 비교된다") {
        AppVersion(1, 0, 0) shouldBeLessThan AppVersion(2, 0, 0)
        AppVersion(1, 2, 0) shouldBeGreaterThan AppVersion(1, 1, 9)
        AppVersion(1, 1, 2) shouldBeGreaterThan AppVersion(1, 1, 1)
        AppVersion(1, 1, 1).compareTo(AppVersion(1, 1, 1)) shouldBe 0
    }

    test("자산 이름에 들어가는 표기에는 v 가 붙지 않는다") {
        AppVersion(1, 0, 0).toString() shouldBe "1.0.0"
    }
})
