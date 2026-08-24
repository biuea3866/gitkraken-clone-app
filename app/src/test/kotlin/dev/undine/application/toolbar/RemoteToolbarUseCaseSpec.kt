package dev.undine.application.toolbar

import dev.undine.domain.CommitId
import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway
import dev.undine.domain.RemoteRef
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException

private const val REMOTE = "origin"
private val BRANCH = RefName("refs/heads/main")
private val REMOTE_REF = RemoteRef(
    remote = REMOTE,
    name = RefName("refs/remotes/origin/main"),
    target = CommitId.of("a".repeat(40)),
)

/**
 * 툴바가 쓰는 원격 UseCase 3종. UseCase 는 Gateway 호출을 그대로 옮기는 얇은 층이므로
 * 검증 대상은 **전달**이다 — 인자·진행률·결과·예외가 손실 없이 오가는지 본다.
 */
class RemoteToolbarUseCaseSpec : BehaviorSpec({

    given("fetch UseCase") {

        `when`("원격 이름으로 실행하면") {
            then("Gateway 가 돌려준 원격 참조 목록을 그대로 전달한다") {
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.fetch(REMOTE, any()) } returns listOf(REMOTE_REF)

                FetchRemoteUseCase(remoteGateway).execute(REMOTE) { } shouldBe listOf(REMOTE_REF)
                coVerify(exactly = 1) { remoteGateway.fetch(REMOTE, any()) }
            }
        }

        `when`("Gateway 가 진행률을 올리면") {
            then("호출부 콜백이 그대로 받는다") {
                val remoteGateway = mockk<RemoteGateway>()
                val reported = mutableListOf<Progress>()
                coEvery { remoteGateway.fetch(REMOTE, any()) } coAnswers {
                    secondArg<(Progress) -> Unit>().invoke(Progress(0.5, "Receiving objects"))
                    emptyList()
                }

                FetchRemoteUseCase(remoteGateway).execute(REMOTE) { reported += it }

                reported shouldBe listOf(Progress(0.5, "Receiving objects"))
            }
        }

        `when`("인증에 실패하면") {
            then("도메인 예외가 그대로 올라온다") {
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.fetch(REMOTE, any()) } throws
                    UndineException.AuthenticationFailed(REMOTE)

                shouldThrow<UndineException.AuthenticationFailed> {
                    FetchRemoteUseCase(remoteGateway).execute(REMOTE) { }
                }
            }
        }
    }

    given("pull UseCase") {

        `when`("실행하면") {
            then("Gateway 의 pull 을 같은 원격으로 호출한다") {
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.pull(REMOTE, any()) } returns Unit

                PullRemoteUseCase(remoteGateway).execute(REMOTE) { }

                coVerify(exactly = 1) { remoteGateway.pull(REMOTE, any()) }
            }
        }

        `when`("작업이 취소되면") {
            then("CancellationException 을 삼키지 않고 그대로 올린다") {
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.pull(REMOTE, any()) } throws CancellationException("취소")

                shouldThrow<CancellationException> {
                    PullRemoteUseCase(remoteGateway).execute(REMOTE) { }
                }
            }
        }
    }

    given("push UseCase") {

        `when`("일반 push 를 실행하면") {
            then("force 없이 요청하고 수락 결과를 전달한다") {
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.push(BRANCH, false, any()) } returns PushResult.Accepted

                PushRemoteUseCase(remoteGateway).execute(BRANCH, force = false) { } shouldBe
                    PushResult.Accepted
            }
        }

        `when`("force push 를 실행하면") {
            then("force 플래그가 Gateway 까지 전달된다") {
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.push(BRANCH, true, any()) } returns PushResult.Accepted

                PushRemoteUseCase(remoteGateway).execute(BRANCH, force = true) { }

                coVerify(exactly = 1) { remoteGateway.push(BRANCH, true, any()) }
            }
        }

        `when`("원격이 non-fast-forward 로 거절하면") {
            then("거절 결과를 예외로 바꾸지 않고 그대로 전달한다") {
                val remoteGateway = mockk<RemoteGateway>()
                val rejected = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
                coEvery { remoteGateway.push(BRANCH, false, any()) } returns rejected

                PushRemoteUseCase(remoteGateway).execute(BRANCH, force = false) { } shouldBe rejected
            }
        }
    }
})
