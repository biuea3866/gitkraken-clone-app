package dev.undine.application.blame

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.blame.BlameGateway
import dev.undine.domain.blame.BlameResult
import dev.undine.domain.blame.LineRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val PATH = "code.txt"

/**
 * blame UseCase — 얇은 위임이라 검증 대상은 **기본값**이다.
 *
 * 기본값은 결정이다: 공백 무시를 기본으로 켜면 사용자가 요청하지 않은 결과를 보게 되고, 이력 상한이
 * 없으면 큰 저장소에서 화면이 멈춘다. 그 결정이 조용히 바뀌지 않게 고정한다.
 */
class BlameUseCaseSpec : FunSpec({

    test("blame 은 공백 무시를 기본으로 켜지 않는다") {
        val gateway = RecordingBlameGateway()

        LoadBlameUseCase(gateway).execute(PATH, LineRange.of(1, 10))

        gateway.lastIgnoreWhitespace shouldBe false
        gateway.lastRange shouldBe LineRange.of(1, 10)
        gateway.lastAt shouldBe null
    }

    test("공백 무시와 기준 커밋을 주면 그대로 전달한다") {
        val gateway = RecordingBlameGateway()
        val at = CommitId.of("a".repeat(HASH_LENGTH))

        LoadBlameUseCase(gateway).execute(PATH, LineRange.whole(), ignoreWhitespace = true, at = at)

        gateway.lastIgnoreWhitespace shouldBe true
        gateway.lastAt shouldBe at
    }

    test("파일 이력은 상한을 두고 조회한다") {
        val gateway = RecordingBlameGateway()

        LoadFileHistoryUseCase(gateway).execute(PATH)

        // 상한이 없으면 큰 저장소에서 화면이 멈춘다.
        gateway.lastLimit shouldBe DEFAULT_LIMIT
    }

    test("이력 상한을 지정하면 그 값을 쓴다") {
        val gateway = RecordingBlameGateway()

        LoadFileHistoryUseCase(gateway).execute(PATH, limit = 5)

        gateway.lastLimit shouldBe 5
    }
})

private const val HASH_LENGTH = 40

/** UseCase 가 쓰는 기본 상한. 구현과 같은 값이어야 이 테스트가 의미를 갖는다. */
private const val DEFAULT_LIMIT = 100

/** 인자를 기록만 하는 대역. 실제 blame 동작은 `BlameGatewayImplSpec` 이 실제 저장소로 본다. */
private class RecordingBlameGateway : BlameGateway {

    var lastRange: LineRange? = null
        private set
    var lastIgnoreWhitespace: Boolean? = null
        private set
    var lastAt: CommitId? = null
        private set
    var lastLimit: Int? = null
        private set

    override suspend fun blame(
        path: String,
        range: LineRange,
        ignoreWhitespace: Boolean,
        at: CommitId?,
    ): BlameResult {
        lastRange = range
        lastIgnoreWhitespace = ignoreWhitespace
        lastAt = at
        return BlameResult.Lines(emptyList())
    }

    override suspend fun fileHistory(path: String, at: CommitId?, limit: Int): List<Commit> {
        lastAt = at
        lastLimit = limit
        return emptyList()
    }
}
