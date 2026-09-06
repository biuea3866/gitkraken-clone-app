package dev.undine.presentation

import dev.undine.domain.RepositoryPath
import dev.undine.presentation.i18n.MISSING_KEY_MARKER
import dev.undine.presentation.i18n.builtInStringCatalog
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
import java.util.Locale

/** 결정 G22 가 정한 메뉴 4개. 더 늘리지 않는다 — 메뉴바는 발견 경로이고 팔레트가 실행 경로다. */
private val EXPECTED_MENUS = listOf("저장소", "보기", "편집", "도구")

/** 조작할 수 있는 활성 탭. */
private val OPERABLE = ActiveRepository.Operable(RepositoryPath("/tmp/undine"))

/** 탭은 남아 있지만 그 경로를 쓸 수 없는 활성 탭 (`TabAvailability.MissingPath`). */
private val UNAVAILABLE = ActiveRepository.Unavailable(RepositoryPath("/tmp/undine"))

/**
 * 배선되지 않은 기능. 메뉴에 있으면 눌러도 아무 일이 없는 항목이 된다 (결정 G22).
 *
 * patch 는 UND-47 이 화면·DI·목적지까지 배선했으므로 여기서 빠졌다 — 이제 눌리면 화면이 열린다.
 */
private val UNWIRED_KEYWORDS = listOf("업데이트", "update")

/** 이탈 차단 사유의 대역. 문구 자체는 화면 카탈로그의 몫이라 여기서는 전달만 확인한다. */
private const val EXIT_REASON = "적용하는 중입니다"

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

            then("배선되지 않은 자동 업데이트 항목이 없다") {
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
                    AppDestination.PATCH,
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
                    availabilityOf(it, ActiveRepository.None, koreanStrings()) is CommandAvailability.Blocked
                }

                blocked shouldBe AppDestination.entries.filter { it.requiresRepository }
                availabilityOf(AppDestination.BLAME, ActiveRepository.None, koreanStrings())
                    .shouldBeInstanceOf<CommandAvailability.Blocked>()
                    .reason shouldContain "저장소"
            }
        }

        `when`("저장소가 열려 있을 때 판정하면") {
            then("모든 화면의 이동 명령이 열린다") {
                AppDestination.entries.forEach { destination ->
                    availabilityOf(destination, OPERABLE, koreanStrings()) shouldBe CommandAvailability.Available
                }
            }
        }

        `when`("활성 탭이 경로를 잃었을 때 판정하면") {
            then("저장소가 필요한 화면이 경로를 잃은 사유로 막힌다") {
                availabilityOf(AppDestination.BLAME, UNAVAILABLE, koreanStrings())
                    .shouldBeInstanceOf<CommandAvailability.Blocked>()
                    .reason shouldBe koreanStrings().tabs.unavailableRepository
            }

            then("저장소가 필요 없는 화면은 그대로 열린다") {
                availabilityOf(AppDestination.PREFERENCES, UNAVAILABLE, koreanStrings()) shouldBe
                    CommandAvailability.Available
            }
        }
    }

    given("진행 중인 작업이 있는 화면의 이탈 차단 (결정 C3)") {

        // 적용·저장은 저장소와 디스크를 이미 바꾸고 있다. 화면을 떠나면 그 작업의 스코프가 취소돼
        // 사용자 모르게 끊기고, 무엇이 남았는지 알릴 자리까지 함께 사라진다.
        `when`("지금 화면이 떠나면 안 된다고 등록했으면") {
            then("다른 화면으로 옮기지 못하고 사유를 말한다") {
                val navigation = AppNavigationState(AppDestination.PATCH)
                navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON }

                navigation.exitBlockedReason(AppDestination.REPOSITORY) shouldBe EXIT_REASON
                navigation.go(AppDestination.REPOSITORY)
                navigation.destination shouldBe AppDestination.PATCH
            }

            // 뒤로 가기만 막고 팔레트를 열어 두면 사용자는 그쪽으로 빠져나가고, 작업은 그대로 끊긴다.
            then("팔레트 이동 명령도 저장소 판정보다 먼저 이 사유로 막힌다") {
                availabilityOf(AppDestination.REPOSITORY, OPERABLE, koreanStrings(), EXIT_REASON)
                    .shouldBeInstanceOf<CommandAvailability.Blocked>()
                    .reason shouldBe EXIT_REASON
            }

            then("지금 보고 있는 화면으로 다시 가는 것은 막지 않는다 — 떠나지 않으므로 잃을 작업이 없다") {
                val navigation = AppNavigationState(AppDestination.PATCH)
                navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON }

                navigation.exitBlockedReason(AppDestination.PATCH).shouldBeNull()
            }
        }

        `when`("작업이 끝나 판정이 사유를 내지 않으면") {
            then("차단이 곧바로 풀린다 — 해제를 따로 기억하지 않는다") {
                val navigation = AppNavigationState(AppDestination.PATCH)
                var mutating = true
                navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON.takeIf { mutating } }

                navigation.go(AppDestination.REPOSITORY)
                navigation.destination shouldBe AppDestination.PATCH

                mutating = false
                navigation.exitBlockedReason(AppDestination.REPOSITORY).shouldBeNull()
                navigation.go(AppDestination.REPOSITORY)
                navigation.destination shouldBe AppDestination.REPOSITORY
            }
        }

        `when`("화면이 컴포지션을 떠나 등록을 거뒀으면") {
            then("남은 등록이 다음 이동을 막지 않는다") {
                val navigation = AppNavigationState(AppDestination.PATCH)
                navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON }
                navigation.releaseExitFrom(AppDestination.PATCH)

                navigation.exitBlockedReason(AppDestination.REPOSITORY).shouldBeNull()
                navigation.go(AppDestination.REPOSITORY)
                navigation.destination shouldBe AppDestination.REPOSITORY
            }
        }

        `when`("다른 화면이 남긴 등록만 있으면") {
            then("지금 화면의 이동은 막히지 않는다") {
                val navigation = AppNavigationState(AppDestination.BLAME)
                navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON }

                navigation.exitBlockedReason(AppDestination.REPOSITORY).shouldBeNull()
                navigation.activeJobBlockedReason().shouldBeNull()
            }
        }
    }

    given("진행 중인 작업이 저장소 전환도 막는 판정 (결정 C6)") {

        // 이동만 막고 세션 전환을 열어 두면 같은 구멍으로 빠져나간다 — 그때 검사한 저장소와 실제
        // 적용 대상이 갈린다.
        `when`("지금 화면이 진행 중인 작업을 등록했으면") {
            then("이동 차단과 저장소 전환 차단이 같은 사유를 낸다") {
                val navigation = AppNavigationState(AppDestination.PATCH)
                navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON }

                navigation.activeJobBlockedReason() shouldBe EXIT_REASON
                navigation.activeJobBlockedReason() shouldBe navigation.exitBlockedReason(AppDestination.REPOSITORY)
            }

            // 이동은 "지금 화면으로 다시 가기" 를 열어 두지만, 저장소 전환에는 그런 예외가 없다 —
            // 어느 화면에 있든 활성 세션이 바뀌면 적용 대상이 바뀐다.
            then("지금 화면에 머무는 것과 무관하게 저장소 전환은 막힌다") {
                val navigation = AppNavigationState(AppDestination.PATCH)
                navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON }

                navigation.exitBlockedReason(AppDestination.PATCH).shouldBeNull()
                navigation.activeJobBlockedReason() shouldBe EXIT_REASON
            }
        }

        `when`("작업이 끝났거나 등록이 거둬졌으면") {
            then("저장소 전환 차단도 곧바로 풀린다") {
                val navigation = AppNavigationState(AppDestination.PATCH)
                var mutating = true
                navigation.blockExitFrom(AppDestination.PATCH) { EXIT_REASON.takeIf { mutating } }

                navigation.activeJobBlockedReason() shouldBe EXIT_REASON
                mutating = false
                navigation.activeJobBlockedReason().shouldBeNull()

                navigation.releaseExitFrom(AppDestination.PATCH)
                navigation.activeJobBlockedReason().shouldBeNull()
            }
        }
    }

    given("저장소를 바꾸는 명령의 차단 사유 (결정 G43)") {

        `when`("활성 탭이 경로를 잃었으면") {
            // "저장소를 찾을 수 없습니다" 로 끝내면 사용자가 할 수 있는 일이 없다 (결정 G43).
            then("무엇이 왜 막혔는지와 다음 행동을 함께 말한다") {
                // 문구를 한국어로 단언하므로 **로캘을 명시해서 받는다.** 시스템 기본 로캘에 기대면
                // 영어로 도는 기계(CI)에서 같은 코드가 깨진다 — 검증 대상은 로캘이 아니라 문구다.
                val reason = repositoryChangeBlockedReason(UNAVAILABLE, koreanStrings())

                reason.shouldBeInstanceOf<String>()
                reason shouldContain "탭"
                reason shouldNotContainText MISSING_KEY_MARKER
            }

            then("i18n 리소스에서 온 문구다 — 코드에 박은 문자열이 아니다") {
                repositoryChangeBlockedReason(UNAVAILABLE, koreanStrings()) shouldBe
                    koreanStrings().tabs.unavailableRepository
            }
        }

        `when`("조작할 수 있거나 열린 탭이 없으면") {
            then("이 판정은 막지 않는다 — 명령 자신의 조건에 맡긴다") {
                repositoryChangeBlockedReason(OPERABLE, koreanStrings()).shouldBeNull()
                repositoryChangeBlockedReason(ActiveRepository.None, koreanStrings()).shouldBeNull()
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

/** 한국어 문구를 단언할 때 쓰는 카탈로그 — 시스템 로캘에 기대지 않는다. */
private fun koreanStrings() = builtInStringCatalog().stringsFor(Locale.KOREAN)
