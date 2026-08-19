package dev.undine.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val LOWER_HASH = "3f2a1b4c5d6e7f8091a2b3c4d5e6f708192a3b4c"
private const val UPPER_HASH = "3F2A1B4C5D6E7F8091A2B3C4D5E6F708192A3B4C"

private val INVALID_HASHES = mapOf(
    "39자" to LOWER_HASH.dropLast(1),
    "41자" to LOWER_HASH + "0",
    "hex 아닌 문자(g)를 포함한 40자" to LOWER_HASH.dropLast(1) + "g",
    "빈 문자열" to "",
)

class CommitIdSpec : FunSpec({

    test("40자 hexadecimal 해시는 그대로 생성된다") {
        CommitId.of(LOWER_HASH).value shouldBe LOWER_HASH
    }

    test("대문자 40자 해시는 소문자로 정규화돼 저장된다") {
        CommitId.of(UPPER_HASH).value shouldBe LOWER_HASH
    }

    test("대소문자만 다른 같은 해시는 동치다") {
        CommitId.of(UPPER_HASH) shouldBe CommitId.of(LOWER_HASH)
    }

    test("toString 은 정규화된 해시 값을 반환한다") {
        CommitId.of(UPPER_HASH).toString() shouldBe LOWER_HASH
    }

    INVALID_HASHES.forEach { (description, raw) ->
        test("$description 은 InvalidCommitId 를 던진다") {
            val thrown = shouldThrow<UndineException.InvalidCommitId> { CommitId.of(raw) }
            thrown.raw shouldBe raw
        }
    }
})
