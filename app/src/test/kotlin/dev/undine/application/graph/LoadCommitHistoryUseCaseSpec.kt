package dev.undine.application.graph

import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.testsupport.HistoryRequest
import dev.undine.testsupport.RecordingHistoryGateway
import dev.undine.testsupport.commit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private val REFS = listOf(RefName("refs/heads/main"))
private const val PAGE_SIZE = 2

/** 이력 로딩 UseCase — presentation 이 Gateway 를 직접 잡지 않게 하는 application 경계다. */
class LoadCommitHistoryUseCaseSpec : BehaviorSpec({

    given("커밋 4건이 있는 저장소") {
        val commits = listOf(commit(1, 2), commit(2, 3), commit(3, 4), commit(4))

        `when`("첫 페이지를 offset 0 으로 요청하면") {
            val gateway = RecordingHistoryGateway(commits)
            val useCase = LoadCommitHistoryUseCase(gateway)
            val loaded = useCase.execute(REFS, offset = 0, limit = PAGE_SIZE)

            then("요청 인자가 그대로 Gateway 에 전달되고 앞 2건이 돌아온다") {
                gateway.requests shouldContainExactly listOf(HistoryRequest(REFS, 0, PAGE_SIZE))
                loaded shouldContainExactly commits.take(PAGE_SIZE)
            }
        }

        `when`("후속 페이지를 offset 2 로 요청하면") {
            val gateway = RecordingHistoryGateway(commits)
            val useCase = LoadCommitHistoryUseCase(gateway)
            val loaded = useCase.execute(REFS, offset = PAGE_SIZE, limit = PAGE_SIZE)

            then("이어지는 2건이 돌아온다") {
                gateway.requests shouldContainExactly listOf(HistoryRequest(REFS, PAGE_SIZE, PAGE_SIZE))
                loaded shouldContainExactly commits.drop(PAGE_SIZE)
            }
        }
    }

    given("참조 목록이 비어 있는 저장소") {
        `when`("이력을 요청하면") {
            val gateway = RecordingHistoryGateway()
            val loaded = LoadCommitHistoryUseCase(gateway).execute(emptyList(), offset = 0, limit = PAGE_SIZE)

            then("빈 목록이 돌아온다") {
                loaded shouldBe emptyList()
            }
        }
    }

    given("Gateway 가 실패하는 저장소") {
        val failure = UndineException.GitOperationFailed("history")

        `when`("이력을 요청하면") {
            val useCase = LoadCommitHistoryUseCase(RecordingHistoryGateway(failure = failure))

            then("실패를 빈 목록으로 바꾸지 않고 그대로 올린다") {
                val thrown = shouldThrow<UndineException.GitOperationFailed> {
                    useCase.execute(REFS, offset = 0, limit = PAGE_SIZE)
                }
                thrown shouldBeSameInstanceAs failure
            }
        }
    }

    given("로딩이 진행 중인 저장소") {
        `when`("요청 코루틴을 취소하면") {
            val gate = CompletableDeferred<Unit>()
            val gateway = RecordingHistoryGateway(gate = gate)
            val useCase = LoadCommitHistoryUseCase(gateway)
            var completed = false

            val scope = CoroutineScope(Dispatchers.Default)
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                useCase.execute(REFS, offset = 0, limit = PAGE_SIZE)
                completed = true
            }
            job.cancel()
            job.join()
            yield()

            then("로딩이 중단되고 성공으로 완료되지 않는다") {
                job.isCancelled shouldBe true
                completed shouldBe false
                gateway.requests.size shouldBe 1
            }
        }
    }
})
