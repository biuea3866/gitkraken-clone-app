package dev.undine.presentation

import dev.undine.domain.RepositoryPath
import dev.undine.presentation.i18n.MISSING_KEY_MARKER
import dev.undine.presentation.i18n.systemStrings
import dev.undine.presentation.i18n.tabs
import dev.undine.presentation.palette.CommandAvailability
import dev.undine.presentation.shell.ActiveRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain as shouldNotContainText
import io.kotest.matchers.types.shouldBeInstanceOf

/** 결정 G22 가 정한 메뉴 4개. 더 늘리지 않는다 — 메뉴바는 발견 경로이고 팔레트가 실행 경로다. */
private val EXPECTED_MENUS = listOf("저장소", "보기", "편집", "도구")

/** 조작할 수 있는 활성 탭. */
private val OPERABLE = ActiveRepository.Operable(RepositoryPath("/tmp/undine"))

/** 탭은 남아 있지만 그 경로를 쓸 수 없는 활성 탭 (`TabAvailability.MissingPath`). */
private val UNAVAILABLE = ActiveRepository.Unavailable(RepositoryPath("/tmp/undine"))

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
                    destinationFor(requested, OPERABLE) shouldBe requested
                }
            }
        }

        `when`("저장소가 없으면") {
            then("저장소가 필요한 화면은 시작 화면으로 되돌아간다") {
                AppDestination.entries.filter { it.requiresRepository }.forEach { requested ->
                    destinationFor(requested, ActiveRepository.None) shouldBe AppDestination.WELCOME
                }
            }

            then("설정·시작 화면은 저장소 없이도 그대로 열린다") {
                destinationFor(AppDestination.PREFERENCES, ActiveRepository.None) shouldBe
                    AppDestination.PREFERENCES
                destinationFor(AppDestination.WELCOME, ActiveRepository.None) shouldBe AppDestination.WELCOME
            }

            // 이력은 현재 열린 저장소의 것이다 — 저장소 없이 열면 빈 패널만 남는다.
            then("Undo 이력도 저장소가 없으면 시작 화면으로 되돌아간다") {
                destinationFor(AppDestination.UNDO, ActiveRepository.None) shouldBe AppDestination.WELCOME
            }
        }

        // 시작 화면으로 보내면 셸이 통째로 사라져 **탭 막대까지 함께** 사라진다 — 그러면 사용자는
        // 다른 탭을 고르지도, 이 탭을 닫지도 못한 채 갇힌다 (UND-81 이 남긴 상태).
        `when`("활성 탭이 경로를 잃었으면") {
            then("저장소가 필요한 화면은 시작 화면이 아니라 저장소 셸로 간다 — 탭 막대가 남는다") {
                AppDestination.entries.filter { it.requiresRepository }.forEach { requested ->
                    destinationFor(requested, UNAVAILABLE) shouldBe AppDestination.REPOSITORY
                }
            }

            then("설정·시작 화면은 요청한 그대로 열린다") {
                destinationFor(AppDestination.PREFERENCES, UNAVAILABLE) shouldBe AppDestination.PREFERENCES
                destinationFor(AppDestination.WELCOME, UNAVAILABLE) shouldBe AppDestination.WELCOME
            }
        }
    }

    given("이동 명령의 가용성") {

        `when`("저장소가 없을 때 판정하면") {
            then("저장소가 필요한 화면만 사유와 함께 막힌다") {
                val blocked = AppDestination.entries.filter {
                    availabilityOf(it, ActiveRepository.None) is CommandAvailability.Blocked
                }

                blocked shouldBe AppDestination.entries.filter { it.requiresRepository }
                availabilityOf(AppDestination.BLAME, ActiveRepository.None)
                    .shouldBeInstanceOf<CommandAvailability.Blocked>()
                    .reason shouldContain "저장소"
            }
        }

        `when`("저장소가 열려 있을 때 판정하면") {
            then("모든 화면의 이동 명령이 열린다") {
                AppDestination.entries.forEach { destination ->
                    availabilityOf(destination, OPERABLE) shouldBe CommandAvailability.Available
                }
            }
        }

        `when`("활성 탭이 경로를 잃었을 때 판정하면") {
            then("저장소가 필요한 화면이 경로를 잃은 사유로 막힌다") {
                availabilityOf(AppDestination.BLAME, UNAVAILABLE)
                    .shouldBeInstanceOf<CommandAvailability.Blocked>()
                    .reason shouldBe systemStrings().tabs.unavailableRepository
            }

            then("저장소가 필요 없는 화면은 그대로 열린다") {
                availabilityOf(AppDestination.PREFERENCES, UNAVAILABLE) shouldBe CommandAvailability.Available
            }
        }
    }

    given("저장소를 바꾸는 명령의 차단 사유 (결정 G43)") {

        `when`("활성 탭이 경로를 잃었으면") {
            // "저장소를 찾을 수 없습니다" 로 끝내면 사용자가 할 수 있는 일이 없다 (결정 G43).
            then("무엇이 왜 막혔는지와 다음 행동을 함께 말한다") {
                val reason = repositoryChangeBlockedReason(UNAVAILABLE)

                reason.shouldBeInstanceOf<String>()
                reason shouldContain "탭"
                reason shouldNotContainText MISSING_KEY_MARKER
            }

            then("i18n 리소스에서 온 문구다 — 코드에 박은 문자열이 아니다") {
                repositoryChangeBlockedReason(UNAVAILABLE) shouldBe systemStrings().tabs.unavailableRepository
            }
        }

        `when`("조작할 수 있거나 열린 탭이 없으면") {
            then("이 판정은 막지 않는다 — 명령 자신의 조건에 맡긴다") {
                repositoryChangeBlockedReason(OPERABLE).shouldBeNull()
                repositoryChangeBlockedReason(ActiveRepository.None).shouldBeNull()
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
