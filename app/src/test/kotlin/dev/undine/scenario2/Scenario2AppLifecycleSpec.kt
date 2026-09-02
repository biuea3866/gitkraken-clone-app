package dev.undine.scenario2

import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.File

private const val BLOCK_FAILURE = "시나리오 단정 실패"

/**
 * 공통 fixture 의 **세션 종료 보장** 회귀 테스트 — [use] 의 `finally` 를 직접 관찰한다.
 *
 * 다른 스펙은 모두 [use] 를 **쓰기만** 하므로 그 `finally` 가 사라져도 초록불로 통과한다. 여기서는
 * 블록 밖에 앱 참조를 남겨 두고 종료 **뒤에** 조회해, 세션이 실제로 닫혔는지 본다.
 *
 * 종료 경로 셋을 모두 본다 — 정상·예외·**취소**. 취소 경로는 `finally` 만으로는 덮이지 않는다:
 * 정리가 [kotlinx.coroutines.NonCancellable] 밖에 있으면 `close()` 의 `withContext` 가 실행되지
 * 않고 세션이 남는다 (결정 A-L2·G46). 앞의 두 경로만 보는 테스트는 그 결함을 잡지 못한다.
 *
 * 자원 해제는 `RepositoryTabsScenario2Spec` 과 같은 기준으로 본다 — 닫힌 뒤의 저장소 조회는 빈 결과가
 * 아니라 실패여야 한다. JGit 이 핸들 수를 노출하지 않으므로 "핸들 0" 을 세지 않는다.
 */
class Scenario2AppLifecycleSpec : FunSpec({

    test("use 블록이 정상 종료하면 세션 자원이 해제된다") {
        val app = scenario2AppAt(seedRepository(File(tempdir(), "work")))

        app.use { opened ->
            opened.open()
            opened.readActiveRepository { repository -> repository.branch } shouldBe MAIN_BRANCH
        }

        app.shouldHaveClosedSession()
    }

    test("use 블록이 예외로 빠져나가도 세션 자원이 해제된다") {
        val app = scenario2AppAt(seedRepository(File(tempdir(), "work")))

        val failure = shouldThrow<IllegalStateException> {
            app.use { opened ->
                opened.open()
                // 시나리오가 단정 실패로 빠져나가는 경로. 이때 핸들이 남으면 뒤 스펙이 흔들린다.
                error(BLOCK_FAILURE)
            }
        }

        failure.message shouldBe BLOCK_FAILURE
        app.shouldHaveClosedSession()
    }

    test("use 블록이 취소로 빠져나가도 세션 자원이 해제된다") {
        val app = scenario2AppAt(seedRepository(File(tempdir(), "work")))
        val started = CompletableDeferred<Unit>()
        var propagated: Throwable? = null

        // 디스패처를 지정하지 않고 테스트 코루틴의 자식으로 띄운다 — 취소 대상은 이 job 하나다.
        val job = launch {
            try {
                app.use { opened ->
                    opened.open()
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (cancellation: CancellationException) {
                // 삼키지 않고 다시 던진다 — 잡는 이유는 타입을 단정하기 위해서다.
                propagated = cancellation
                throw cancellation
            }
        }
        started.await()
        // join 은 NonCancellable 정리까지 끝난 뒤에 돌아온다 — 그래서 세션 상태를 바로 검증할 수 있다.
        job.cancelAndJoin()

        propagated.shouldBeInstanceOf<CancellationException>()
        app.shouldHaveClosedSession()
    }
})

private suspend fun Scenario2App.shouldHaveClosedSession() {
    val failure = shouldThrow<UndineException.StateViolation> {
        readActiveRepository { repository -> repository.repositoryState }
    }
    failure.message.orEmpty() shouldContain "열려 있지"
}
