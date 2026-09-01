package dev.undine.application.gitconfig

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.gitconfig.EffectiveValue
import dev.undine.domain.gitconfig.GitConfigGateway
import dev.undine.domain.gitconfig.GitConfigKey
import dev.undine.domain.gitconfig.GitConfigSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException

private val REPOSITORY = RepositoryPath("/tmp/repo")

/**
 * UseCase 는 Gateway 결과를 **그대로** 통과시킨다 — 여기서 부재를 기본값으로 바꾸거나 실패를
 * 부재로 접으면 결정 G35 2("부재와 실패를 절대 섞지 않는다")가 깨진다.
 */
class ReadEffectiveConfigUseCaseSpec : BehaviorSpec({

    given("Git 설정에 값이 있는 저장소") {
        val gateway = mockk<GitConfigGateway>()
        val values = mapOf(
            GitConfigKey.USER_EMAIL to EffectiveValue("me@work.example", GitConfigSource.REPOSITORY),
            GitConfigKey.INIT_DEFAULT_BRANCH to EffectiveValue("main", GitConfigSource.GLOBAL),
        )
        coEvery { gateway.effectiveValues(REPOSITORY) } returns values

        `when`("실효값을 조회하면") {
            val result = ReadEffectiveConfigUseCase(gateway).execute(REPOSITORY)

            then("값과 출처를 그대로 돌려준다") {
                result shouldBe values
                result.getValue(GitConfigKey.USER_EMAIL).source shouldBe GitConfigSource.REPOSITORY
                result.getValue(GitConfigKey.INIT_DEFAULT_BRANCH).source shouldBe GitConfigSource.GLOBAL
            }
        }
    }

    given("저장소가 열려 있지 않은 상태") {
        val gateway = mockk<GitConfigGateway>()
        coEvery { gateway.effectiveValues(null) } returns
            mapOf(GitConfigKey.USER_NAME to EffectiveValue("전역 사용자", GitConfigSource.GLOBAL))

        `when`("저장소 없이 조회하면") {
            val result = ReadEffectiveConfigUseCase(gateway).execute(null)

            then("저장소 없이 Gateway 를 부르고 전역 값을 받는다") {
                result.getValue(GitConfigKey.USER_NAME).source shouldBe GitConfigSource.GLOBAL
                coVerify(exactly = 1) { gateway.effectiveValues(null) }
            }
        }
    }

    given("세 범위 어디에도 키가 없는 설정") {
        val gateway = mockk<GitConfigGateway>()
        coEvery { gateway.effectiveValues(REPOSITORY) } returns emptyMap()

        `when`("실효값을 조회하면") {
            val result = ReadEffectiveConfigUseCase(gateway).execute(REPOSITORY)

            then("부재를 그대로 돌려준다 — 앱 설정으로 대체하지 않는다") {
                result.shouldBeEmpty()
            }
        }
    }

    given("설정 파일을 읽지 못하는 저장소") {
        val gateway = mockk<GitConfigGateway>()
        val failure = UndineException.GitOperationFailed("read git config", IOException("깨진 설정"))
        coEvery { gateway.effectiveValues(REPOSITORY) } throws failure

        `when`("실효값을 조회하면") {
            then("부재나 성공으로 접지 않고 실패를 그대로 전파한다") {
                val thrown = shouldThrow<UndineException.GitOperationFailed> {
                    ReadEffectiveConfigUseCase(gateway).execute(REPOSITORY)
                }
                thrown.operation shouldBe "read git config"
                thrown shouldBe failure
            }
        }
    }
})
