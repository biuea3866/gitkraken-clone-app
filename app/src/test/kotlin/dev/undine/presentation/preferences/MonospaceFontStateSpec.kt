package dev.undine.presentation.preferences

import dev.undine.application.typography.LoadMonospaceFontsUseCase
import dev.undine.domain.typography.MonospaceFontGateway
import dev.undine.domain.typography.MonospaceFontListing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

private const val SAVED_FONT = "사내 고정폭"

/** 정해 둔 열거 결과만 돌려주는 Gateway. 실제 측정은 `MonospaceFontGatewayImplSpec` 이 검증한다. */
private class ListingGateway(private val listing: MonospaceFontListing) : MonospaceFontGateway {

    var calls: Int = 0
        private set

    override suspend fun monospaceFamilies(): MonospaceFontListing {
        calls += 1
        return listing
    }
}

private fun stateOf(gateway: ListingGateway): MonospaceFontState = MonospaceFontState(
    scope = CoroutineScope(Dispatchers.Unconfined + Job()),
    loadMonospaceFonts = LoadMonospaceFontsUseCase(gateway),
).also(MonospaceFontState::refresh)

/**
 * 고정폭 서체 후보의 상태와 선택지 조립.
 *
 * 보는 것은 경계다: 열거에 실패해도 직접 입력이 남는가, 후보에 없는 저장값을 지우지 않는가,
 * "물어보지 못했다" 와 "하나도 없다" 를 섞지 않는가.
 */
class MonospaceFontStateSpec : FunSpec({

    test("열거에 성공하면 후보를 그대로 싣는다") {
        val state = stateOf(ListingGateway(MonospaceFontListing.Available(listOf("D2Coding", "Menlo"))))

        state.candidates shouldContainExactly listOf("D2Coding", "Menlo")
        state.isListingUnavailable shouldBe false
    }

    test("고정폭 서체를 하나도 찾지 못한 것도 성공이다 — 물어보지 못한 것과 다르다") {
        val state = stateOf(ListingGateway(MonospaceFontListing.Available(emptyList())))

        state.candidates.shouldBeEmpty()
        state.isListingUnavailable shouldBe false
    }

    test("아직 묻지 않았으면 결과가 없다 — 빈 성공과 섞이지 않는다") {
        val state = MonospaceFontState(
            scope = CoroutineScope(Dispatchers.Unconfined + Job()),
            loadMonospaceFonts = LoadMonospaceFontsUseCase(
                ListingGateway(MonospaceFontListing.Available(listOf("Menlo"))),
            ),
        )

        state.listing.shouldBeNull()
        state.candidates.shouldBeEmpty()
    }

    test("열거에 실패하면 후보가 비지만 실패한 사실이 남는다") {
        val state = stateOf(ListingGateway(MonospaceFontListing.Unavailable(IllegalStateException("헤드리스"))))

        state.candidates.shouldBeEmpty()
        // 화면은 이 값으로 "직접 입력하세요" 를 안내한다 — 빈 목록만으로는 왜 비었는지 말할 수 없다.
        state.isListingUnavailable shouldBe true
    }

    test("열거가 실패해도 직접 입력한 값이 선택지에 남는다") {
        val state = stateOf(ListingGateway(MonospaceFontListing.Unavailable(IllegalStateException("헤드리스"))))

        monospaceFontChoices(state.candidates, SAVED_FONT) shouldContainExactly listOf(SAVED_FONT)
    }

    test("후보에 없는 저장값은 지우지 않고 선택지에 더한다") {
        val choices = monospaceFontChoices(listOf("D2Coding", "Menlo"), SAVED_FONT)

        choices shouldContainExactly listOf("D2Coding", "Menlo", SAVED_FONT).sorted()
    }

    test("후보에 이미 있는 저장값은 두 번 나오지 않는다") {
        monospaceFontChoices(listOf("D2Coding", "Menlo"), "Menlo") shouldContainExactly
            listOf("D2Coding", "Menlo")
    }

    test("저장값이 없거나 공백뿐이면 후보만 내준다 — 빈 선택지를 만들지 않는다") {
        monospaceFontChoices(listOf("Menlo"), null) shouldContainExactly listOf("Menlo")
        monospaceFontChoices(listOf("Menlo"), "   ") shouldContainExactly listOf("Menlo")
    }

    test("후보도 저장값도 없으면 선택지가 비어 화면이 고르기 자리를 만들지 않는다") {
        monospaceFontChoices(emptyList(), null).shouldBeEmpty()
    }
})
