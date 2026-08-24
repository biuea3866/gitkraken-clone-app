package dev.undine.application.welcome

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val ALPHA = RepositoryPath("/tmp/alpha")
private val BETA = RepositoryPath("/tmp/beta")
private val BROKEN = RepositoryPath("/tmp/broken")

/** 저장소 열기 UseCase — 열기 성공 시에만 최근 목록을 갱신한다. */
class OpenRepositoryUseCaseSpec : BehaviorSpec({

    given("최근 목록에 두 저장소가 있는 설정") {
        `when`("목록 뒤쪽 저장소를 열면") {
            then("열린 저장소가 최근 목록 맨 앞으로 이동해 저장된다") {
                val settings = FakeSettingsGateway(settingsWith(listOf(ALPHA, BETA)))
                val repositories = FakeRepositoryGateway()
                val useCase = OpenRepositoryUseCase(repositories, settings)

                useCase.execute(BETA)

                repositories.openedPaths shouldContainExactly listOf(BETA)
                settings.stored.recentRepositories shouldContainExactly listOf(BETA, ALPHA)
            }
        }

        `when`("처음 보는 저장소를 열면") {
            then("맨 앞에 추가되고 기존 항목이 뒤로 밀린다") {
                val settings = FakeSettingsGateway(settingsWith(listOf(ALPHA)))
                val useCase = OpenRepositoryUseCase(FakeRepositoryGateway(), settings)

                val opened = useCase.execute(BETA)

                opened.currentBranch shouldBe dev.undine.domain.RefName("refs/heads/main")
                settings.stored.recentRepositories shouldContainExactly listOf(BETA, ALPHA)
            }
        }
    }

    given("Git 저장소가 아닌 경로") {
        `when`("그 경로를 열면") {
            then("예외가 그대로 올라오고 최근 목록은 저장되지 않는다") {
                val settings = FakeSettingsGateway(settingsWith(listOf(ALPHA)))
                val repositories = FakeRepositoryGateway(
                    failures = mapOf(
                        BROKEN to UndineException.InvalidRepositoryPath(
                            raw = BROKEN.value,
                            reason = UndineException.InvalidRepositoryPath.Reason.NOT_A_REPOSITORY,
                        ),
                    ),
                )
                val useCase = OpenRepositoryUseCase(repositories, settings)

                val failure = shouldThrow<UndineException.InvalidRepositoryPath> { useCase.execute(BROKEN) }

                failure.reason shouldBe UndineException.InvalidRepositoryPath.Reason.NOT_A_REPOSITORY
                settings.saveCount shouldBe 0
                settings.stored.recentRepositories shouldContainExactly listOf(ALPHA)
            }
        }
    }

    given("최근 목록에 사라진 경로가 있는 설정") {
        `when`("그 경로를 제거하면") {
            then("목록에서 빠진 설정이 저장되고 남은 목록이 반환된다") {
                val settings = FakeSettingsGateway(settingsWith(listOf(ALPHA, BETA)))
                val useCase = ForgetRecentRepositoryUseCase(settings)

                val remaining = useCase.execute(ALPHA)

                remaining shouldContainExactly listOf(BETA)
                settings.stored.recentRepositories shouldContainExactly listOf(BETA)
            }
        }
    }

    given("최근 목록이 저장된 설정") {
        `when`("최근 목록을 읽으면") {
            then("저장된 순서 그대로 돌려준다") {
                val useCase = LoadRecentRepositoriesUseCase(FakeSettingsGateway(settingsWith(listOf(BETA, ALPHA))))

                useCase.execute() shouldContainExactly listOf(BETA, ALPHA)
            }
        }
    }
})
