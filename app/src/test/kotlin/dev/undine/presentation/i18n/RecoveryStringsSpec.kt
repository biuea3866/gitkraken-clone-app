package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `recovery.*` 문구 계약 — 접근자가 실제 키를 찾고, 인자 있는 문구가 값을 치환하는가.
 *
 * `StringCatalogSpec` 은 카탈로그 전체의 병합·폴백만 본다. 화면이 실제로 쓰는 접근자와 치환 인자는
 * 여기서 확인한다 — 키 오타나 치환 자리 누락은 병합 테스트를 그대로 통과하기 때문이다.
 */
class RecoveryStringsSpec : FunSpec({

    val catalog = builtInStringCatalog()
    val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).recovery
    val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).recovery

    fun accessorsOf(copy: RecoveryStrings): List<String> = listOf(
        copy.title,
        copy.reflog,
        copy.reflogLoading,
        copy.reflogEmpty,
        copy.reflogExpired,
        copy.preview,
        copy.changedFiles,
        copy.loadFailed,
        copy.undoRecordFailed,
        copy.newBranch,
        copy.moveExisting,
        copy.moveWarning,
        copy.moveConfirm,
        copy.moveCancel,
        copy.scanUnreachable,
        copy.scanWarning,
        copy.scanning,
        copy.scanUnsupported,
        copy.bisect,
        copy.bisectStart,
        copy.bisectPickGood,
        copy.bisectPickBad,
        copy.bisectBoundaryMissing,
        copy.summaryUnknownCounts,
        copy.markGood,
        copy.markBad,
        copy.skip,
        copy.reset,
        copy.inconclusive,
        copy.inconclusiveReason,
        copy.historyNotChronological,
    )

    test("두 로케일 모두에서 인자 없는 접근자가 키 이름으로 폴백하지 않는다") {
        listOf(korean, english).forEach { copy ->
            accessorsOf(copy).forEach { text ->
                text.isBlank() shouldBe false
                text shouldNotContain "$RECOVERY_NAMESPACE."
            }
        }
    }

    test("한국어 문구는 실제 번역을 돌려준다") {
        korean.title shouldBe "복구"
        korean.markGood shouldBe "좋음"
        korean.reset shouldBe "Bisect 초기화"
    }

    test("영어 문구는 실제 번역을 돌려준다") {
        english.title shouldBe "Recovery"
        english.markGood shouldBe "Good"
        english.reset shouldBe "Reset bisect"
    }

    test("검사 대상·후보 수·예상 횟수 문구는 인자를 치환한다") {
        listOf(korean, english).forEach { copy ->
            copy.currentTarget("abc1234") shouldContain "abc1234"
            copy.remainingCandidates(5) shouldContain "5"
            copy.remainingChecks(3) shouldContain "3"
            copy.firstBad("dead999") shouldContain "dead999"
        }
    }

    test("경계·이력 문구는 인자를 치환한다") {
        listOf(korean, english).forEach { copy ->
            copy.bisectBoundaryGood("aaa1111") shouldContain "aaa1111"
            copy.bisectBoundaryBad("bbb2222") shouldContain "bbb2222"
            copy.historyGood("ccc3333, ddd4444") shouldContain "ddd4444"
            copy.historyCurrentBad("eee5555") shouldContain "eee5555"
            copy.historySkipped("fff6666") shouldContain "fff6666"
        }
    }

    test("치환 자리 표식이 화면 문구에 그대로 남지 않는다") {
        listOf(korean, english).forEach { copy ->
            copy.currentTarget("abc1234") shouldNotContain "{0}"
            copy.remainingCandidates(5) shouldNotContain "{0}"
            copy.historyGood("ccc3333") shouldNotContain "{0}"
        }
    }
})
