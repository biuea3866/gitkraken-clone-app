package dev.undine.infrastructure.typography

import dev.undine.domain.typography.FontProbe
import dev.undine.domain.typography.GlyphWidths
import dev.undine.domain.typography.MonospaceFontListing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.io.IOException

private val MONOSPACE = GlyphWidths(narrow = 7, wide = 7, space = 7)
private val PROPORTIONAL = GlyphWidths(narrow = 3, wide = 11, space = 4)
private const val PROPORTIONAL_FAMILY = "Georgia"

/**
 * 플랫폼 서체 열거는 가짜 [FontProbe] 로 대체한다 — 개발 기기·CI 에 설치된 서체가 달라도
 * 필터·정렬·캐시 계약을 결정적으로 검증하려는 경계다. 실제 AWT 경로는
 * [AwtFontProbeSpec] 이 따로 확인한다.
 */
class MonospaceFontGatewayImplSpec : FunSpec({

    test("고정폭 family 만 중복 없이 이름 오름차순으로 돌려준다") {
        val probe = RecordingFontProbe(listOf("Menlo", PROPORTIONAL_FAMILY, "Fira Code", "Menlo"))

        val listing = runBlocking { MonospaceFontGatewayImpl(probe).monospaceFamilies() }

        listing.shouldBeInstanceOf<MonospaceFontListing.Available>()
            .families shouldContainExactly listOf("Fira Code", "Menlo")
    }

    test("첫 성공 뒤의 재조회는 플랫폼 열거를 다시 하지 않는다") {
        val probe = RecordingFontProbe(listOf("Menlo", "Fira Code"))
        val gateway = MonospaceFontGatewayImpl(probe)

        val first = runBlocking { gateway.monospaceFamilies() }
        val second = runBlocking { gateway.monospaceFamilies() }

        second shouldBe first
        probe.enumerationCount shouldBe 1
    }

    test("성공한 빈 결과도 캐시로 재사용한다") {
        val probe = RecordingFontProbe(listOf(PROPORTIONAL_FAMILY))
        val gateway = MonospaceFontGatewayImpl(probe)

        runBlocking { gateway.monospaceFamilies() } shouldBe MonospaceFontListing.Available(emptyList())
        runBlocking { gateway.monospaceFamilies() } shouldBe MonospaceFontListing.Available(emptyList())

        probe.enumerationCount shouldBe 1
    }

    test("열거 실패는 빈 목록 성공으로 위장하지 않고 사유를 실어 올린다") {
        val failure = IOException("서체 subsystem 을 쓸 수 없습니다")
        val probe = RecordingFontProbe(families = emptyList(), failWith = failure)

        val listing = runBlocking { MonospaceFontGatewayImpl(probe).monospaceFamilies() }

        listing.shouldBeInstanceOf<MonospaceFontListing.Unavailable>().cause shouldBe failure
    }

    test("열거 실패는 캐시하지 않아 다음 조회가 다시 시도해 성공한다") {
        val probe = RecordingFontProbe(
            families = listOf("Menlo"),
            failWith = IOException("일시적 실패"),
        )
        val gateway = MonospaceFontGatewayImpl(probe)

        runBlocking { gateway.monospaceFamilies() }.shouldBeInstanceOf<MonospaceFontListing.Unavailable>()
        probe.failWith = null

        val retried = runBlocking { gateway.monospaceFamilies() }

        retried.shouldBeInstanceOf<MonospaceFontListing.Available>()
            .families shouldContainExactly listOf("Menlo")
        probe.enumerationCount shouldBe 2
    }
})

/** 열거 횟수를 세고 실패를 주입할 수 있는 가짜 probe. */
private class RecordingFontProbe(
    private val families: List<String>,
    var failWith: IOException? = null,
) : FontProbe {

    var enumerationCount: Int = 0
        private set

    override fun availableFamilies(): List<String> {
        enumerationCount += 1
        failWith?.let { throw it }
        return families
    }

    override fun glyphWidths(family: String): GlyphWidths =
        if (family == PROPORTIONAL_FAMILY) PROPORTIONAL else MONOSPACE
}
