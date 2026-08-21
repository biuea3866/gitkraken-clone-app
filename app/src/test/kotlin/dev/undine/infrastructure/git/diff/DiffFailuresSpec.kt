package dev.undine.infrastructure.git.diff

import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import java.io.IOException

class DiffFailuresSpec : FunSpec({

    test("취소 예외는 삼키지 않고 그대로 올린다") {
        val cancellation = CancellationException("취소됨")

        shouldThrow<CancellationException> {
            translateDiffFailure("changedFiles", cancellation)
        } shouldBeSameInstanceAs cancellation
    }

    test("호출부 버그인 IllegalArgumentException 은 도메인 예외로 감싸지 않는다") {
        val precondition = IllegalArgumentException("parentIndex 는 0 이상이어야 합니다: -1")

        shouldThrow<IllegalArgumentException> {
            translateDiffFailure("hunksOf", precondition)
        } shouldBeSameInstanceAs precondition
    }

    test("이미 번역된 도메인 예외는 다시 감싸지 않는다") {
        val notFound = UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, "abc")

        shouldThrow<UndineException.NotFound> {
            translateDiffFailure("hunksOf", notFound)
        } shouldBeSameInstanceAs notFound
    }

    test("예상 밖 JGit 실패는 GitOperationFailed 로 번역하고 원인을 보존한다") {
        val cause = IOException("팩 파일이 손상되었습니다")

        val failure = shouldThrow<UndineException.GitOperationFailed> {
            translateDiffFailure("changedFilesUnstaged", cause)
        }

        failure.operation shouldBe "diff.changedFilesUnstaged"
        failure.cause shouldBeSameInstanceAs cause
    }
})
