package dev.undine.application.typography

import dev.undine.domain.typography.MonospaceFontGateway
import dev.undine.domain.typography.MonospaceFontListing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * UseCase 는 Gateway 결과를 **그대로** 올린다. 열거 실패를 빈 목록으로 바꾸면 화면이 "고정폭
 * 서체가 하나도 없다" 로 읽어 직접 입력 경로를 안내하지 못한다 (조용한 fallback 금지).
 */
class LoadMonospaceFontsUseCaseSpec : FunSpec({

    test("Gateway 의 성공 목록을 그대로 전달한다") {
        val gateway = mockk<MonospaceFontGateway>()
        coEvery { gateway.monospaceFamilies() } returns
            MonospaceFontListing.Available(listOf("Fira Code", "Menlo"))

        val listing = runBlocking { LoadMonospaceFontsUseCase(gateway).execute() }

        listing shouldBe MonospaceFontListing.Available(listOf("Fira Code", "Menlo"))
    }

    test("Gateway 실패를 빈 목록 성공으로 바꾸지 않는다") {
        val failure = IOException("서체 subsystem 을 쓸 수 없습니다")
        val gateway = mockk<MonospaceFontGateway>()
        coEvery { gateway.monospaceFamilies() } returns MonospaceFontListing.Unavailable(failure)

        val listing = runBlocking { LoadMonospaceFontsUseCase(gateway).execute() }

        listing.shouldBeInstanceOf<MonospaceFontListing.Unavailable>().cause shouldBe failure
    }

    test("성공한 빈 목록과 열거 실패는 서로 다른 결과다") {
        val empty = MonospaceFontListing.Available(emptyList())
        val unavailable = MonospaceFontListing.Unavailable(IOException("boom"))

        (empty == unavailable) shouldBe false
    }
})
