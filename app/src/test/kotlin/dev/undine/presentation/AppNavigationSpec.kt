package dev.undine.presentation

import dev.undine.presentation.palette.CommandAvailability
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** 결정 G22 가 정한 메뉴 4개. 더 늘리지 않는다 — 메뉴바는 발견 경로이고 팔레트가 실행 경로다. */
private val EXPECTED_MENUS = listOf("저장소", "보기", "편집", "도구")

/** 배선되지 않은 기능. 메뉴에 있으면 눌러도 아무 일이 없는 항목이 된다 (결정 G22). */
private val UNWIRED_KEYWORDS = listOf("패치", "patch", "업데이트", "update")

class AppNavigationSpec : BehaviorSpec({

    given("OS 메뉴바 구조") {

        `when`("메뉴 목록을 읽으면") {
            then("결정이 정한 네 메뉴만 있다") {
                APP_MENUS.map { it.label } shouldBe EXPECTED_MENUS
            }

            then("보기 메뉴가 2차 화면 다섯에 닿는다") {
                val view = APP_MENUS.single { it.label == "보기" }
                view.items.map { it.command } shouldContainAll listOf(
                    AppMenuCommand.Navigate(AppDestination.PREFERENCES),
                    AppMenuCommand.Navigate(AppDestination.BLAME),
                    AppMenuCommand.Navigate(AppDestination.UNDO),
                    AppMenuCommand.Navigate(AppDestination.SUBMODULE_WORKTREE),
                    AppMenuCommand.Navigate(AppDestination.RECOVERY),
                )
            }

            then("저장소 메뉴에는 열기·클론만 있고 탭 전환은 없다") {
                val repository = APP_MENUS.single { it.label == "저장소" }
                repository.items.map { it.command } shouldBe listOf(
                    AppMenuCommand.OpenRepository,
                    AppMenuCommand.Navigate(AppDestination.WELCOME),
                )
            }

            then("편집 메뉴에 되돌리기가 있다") {
                APP_MENUS.single { it.label == "편집" }.items.map { it.command } shouldBe
                    listOf(AppMenuCommand.UndoLast)
            }

            then("배선되지 않은 patch·자동 업데이트 항목이 없다") {
                val labels = APP_MENUS.flatMap { menu -> menu.items.map { it.label.lowercase() } }
                UNWIRED_KEYWORDS.forEach { keyword -> labels shouldNotContain keyword }
                labels.none { label -> UNWIRED_KEYWORDS.any(label::contains) } shouldBe true
            }
        }

        `when`("배선된 화면 전부에 닿는지 확인하면") {
            then("빠짐 없이 통과한다") {
                verifyMenuReachesEveryDestination()
            }
        }

        `when`("메뉴에서 화면 하나가 빠져 있으면") {
            then("조용히 통과하지 않고 시작 시 실패한다") {
                val broken = APP_MENUS.map { menu ->
                    menu.copy(
                        items = menu.items.filterNot {
                            it.command == AppMenuCommand.Navigate(AppDestination.RECOVERY)
                        },
                    )
                }

                val failure = shouldThrow<IllegalStateException> { verifyMenuReachesEveryDestination(broken) }

                failure.message.orEmpty() shouldContain AppDestination.RECOVERY.name
            }
        }
    }

    given("화면 목록") {

        `when`("저장소가 필요한 화면을 가리면") {
            then("설정·시작 화면을 뺀 나머지가 저장소를 요구한다") {
                AppDestination.entries.filter { it.requiresRepository } shouldBe listOf(
                    AppDestination.REPOSITORY,
                    AppDestination.BLAME,
                    AppDestination.UNDO,
                    AppDestination.SUBMODULE_WORKTREE,
                    AppDestination.RECOVERY,
                )
            }
        }

        `when`("명령 id 를 만들면") {
            then("화면마다 서로 다른 키를 얻는다") {
                AppDestination.entries.map { it.commandKey }.toSet().size shouldBe AppDestination.entries.size
            }
        }
    }

    given("배선이 고르는 화면 (App → destinationFor → DestinationArea)") {

        `when`("저장소가 열려 있으면") {
            then("요청한 화면 일곱이 그대로 그려진다") {
                AppDestination.entries.forEach { requested ->
                    destinationFor(requested, repositoryOpen = true) shouldBe requested
                }
            }
        }

        `when`("저장소가 없으면") {
            then("저장소가 필요한 화면은 시작 화면으로 되돌아간다") {
                AppDestination.entries.filter { it.requiresRepository }.forEach { requested ->
                    destinationFor(requested, repositoryOpen = false) shouldBe AppDestination.WELCOME
                }
            }

            then("설정·시작 화면은 저장소 없이도 그대로 열린다") {
                destinationFor(AppDestination.PREFERENCES, repositoryOpen = false) shouldBe
                    AppDestination.PREFERENCES
                destinationFor(AppDestination.WELCOME, repositoryOpen = false) shouldBe AppDestination.WELCOME
            }

            // 이력은 현재 열린 저장소의 것이다 — 저장소 없이 열면 빈 패널만 남는다.
            then("Undo 이력도 저장소가 없으면 시작 화면으로 되돌아간다") {
                destinationFor(AppDestination.UNDO, repositoryOpen = false) shouldBe AppDestination.WELCOME
            }
        }
    }

    given("이동 명령의 가용성") {

        `when`("저장소가 없을 때 판정하면") {
            then("저장소가 필요한 화면만 사유와 함께 막힌다") {
                val blocked = AppDestination.entries.filter {
                    availabilityOf(it, repositoryOpen = false) is CommandAvailability.Blocked
                }

                blocked shouldBe AppDestination.entries.filter { it.requiresRepository }
                availabilityOf(AppDestination.BLAME, repositoryOpen = false)
                    .shouldBeInstanceOf<CommandAvailability.Blocked>()
                    .reason shouldContain "저장소"
            }
        }

        `when`("저장소가 열려 있을 때 판정하면") {
            then("모든 화면의 이동 명령이 열린다") {
                AppDestination.entries.forEach { destination ->
                    availabilityOf(destination, repositoryOpen = true) shouldBe CommandAvailability.Available
                }
            }
        }
    }

    given("화면 전환 상태") {

        `when`("다른 화면으로 이동하면") {
            then("그 화면이 현재 화면이 된다") {
                val navigation = AppNavigationState()

                navigation.go(AppDestination.PREFERENCES)

                navigation.destination shouldBe AppDestination.PREFERENCES
            }
        }
    }
})
