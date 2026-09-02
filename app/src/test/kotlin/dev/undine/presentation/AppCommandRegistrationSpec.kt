package dev.undine.presentation

import androidx.compose.ui.input.key.Key
import dev.undine.di.AppComponent
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import dev.undine.domain.ShortcutBinding
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.graph.GraphDragDropState
import dev.undine.presentation.graph.GraphOperationCallbacks
import dev.undine.presentation.palette.Command
import dev.undine.presentation.palette.CommandAvailability
import dev.undine.presentation.palette.CommandId
import dev.undine.presentation.palette.CommandOutcome
import dev.undine.presentation.palette.CommandRegistry
import dev.undine.presentation.palette.execute
import dev.undine.presentation.palette.Shortcut
import dev.undine.presentation.palette.ShortcutModifier
import dev.undine.presentation.palette.toBinding
import dev.undine.presentation.palette.toShortcutOverrides
import dev.undine.presentation.shell.ActiveRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.File

/** 조작 대상 커밋. 실행까지 가지 않고 확인창만 여는 경로라 값 자체는 아무 커밋이어도 된다. */
private const val COMMIT = "0123456789abcdef0123456789abcdef01234567"

/** 경로를 잃은 탭. 저장소를 바꾸는 명령은 여기서 막혀야 한다 (UND-83). */
private val UNAVAILABLE = ActiveRepository.Unavailable(RepositoryPath("/tmp/사라진-저장소"))

/** 저장소를 바꾸는 명령. 막혔을 때 직전 저장소 핸들에 조작이 적용되면 안 된다. */
private val REPOSITORY_CHANGING_COMMAND_IDS = listOf(
    "undo.last",
    "rebase.openPlan",
    "graph.merge",
    "graph.rebase",
    "graph.cherryPick",
    "graph.resetBranch",
    "graph.moveTag",
)

/** 경로를 잃어도 남아 있어야 하는 명령 — 탭을 닫고 팔레트를 여는 길까지 막으면 갇힌다. */
private val ALWAYS_OPEN_COMMAND_IDS = listOf("repository.close", "palette.open", "repository.open")

/** 화면이 정의한 그래프 조작 명령 다섯. 등록 대상이 빠지면 팔레트에서 영영 못 부른다. */
private val GRAPH_COMMAND_IDS = listOf(
    "graph.merge",
    "graph.rebase",
    "graph.cherryPick",
    "graph.resetBranch",
    "graph.moveTag",
).map(::CommandId)

/**
 * 등록 결과를 관찰할 수 있게 하는 조립. 실제 앱과 같은 순서로 두 등록 함수를 한 레지스트리에 얹는다 —
 * 순서가 다르면 충돌 판정도 달라지므로 여기서 앱과 어긋나면 테스트가 다른 것을 검증한다.
 */
private class RegistrationFixture(
    private val active: ActiveRepository = ActiveRepository.Operable(RepositoryPath("/tmp/undine")),
    private val selected: GraphOperation? = null,
) {
    val navigated = mutableListOf<AppDestination>()
    var openRequested = 0
    var undoRequested = 0
    var rebasePlanRequested = 0
    val registry = CommandRegistry()

    /**
     * 배선이 그래프 화면에 넘기는 것과 **같은** 홀더 하나다 (`App.kt` 의 단일 `remember`).
     * 등록과 화면이 다른 홀더를 잡으면 명령이 화면에 없는 홀더에 확인창을 연다.
     */
    val dragDrop = GraphDragDropState(
        execute = { error("이 테스트는 조작을 실행하지 않는다") },
        scope = CoroutineScope(Job()),
    )
    val callbacks = GraphOperationCallbacks(dragDrop)

    init {
        registerAppCommands(
            registry = registry,
            handlers = AppCommandHandlers(
                onOpenPalette = {},
                onCloseRepository = {},
                onRefreshRefs = {},
                onToggleDiffView = {},
                onOpenRebasePlan = { rebasePlanRequested++ },
                repositoryChangeBlockedReason = { repositoryChangeBlockedReason(active) },
            ),
        )
        registerSecondaryCommands(
            registry = registry,
            handlers = SecondaryCommandHandlers(
                onNavigate = { navigated += it },
                onOpenRepository = { openRequested++ },
                onUndoLast = { undoRequested++ },
                // 앱이 쓰는 판정 그대로다 — 복제하면 앱과 어긋난 규칙을 검증하게 된다.
                availabilityOf = { destination -> availabilityOf(destination, active) },
                repositoryChangeBlockedReason = { repositoryChangeBlockedReason(active) },
            ),
            graphCallbacks = callbacks,
            selectedGraphOperation = { selected },
        )
    }

    fun commandOf(id: String): Command = registry.commands.single { it.id == CommandId(id) }
}

/** 저장 형식으로 만든 오버라이드 하나. 기존 기본 단축키와 겹치지 않게 수식키 둘을 함께 쓴다. */
private fun bindingOf(key: Key): ShortcutBinding =
    Shortcut(key, setOf(ShortcutModifier.PRIMARY, ShortcutModifier.ALT)).toBinding()

class AppCommandRegistrationSpec : BehaviorSpec({

    given("1차·2차 명령을 한 레지스트리에 등록한 앱") {

        `when`("등록이 끝나면") {
            then("단축키 충돌 없이 모두 등록된다") {
                val fixture = RegistrationFixture()

                // 등록 자체가 충돌을 예외로 거부하므로 여기 도달한 것이 곧 충돌 없음이다.
                val boundShortcuts = fixture.registry.commands.mapNotNull(fixture.registry::effectiveShortcutOf)
                boundShortcuts.toSet().size shouldBe boundShortcuts.size
            }

            then("화면이 정의한 그래프 조작 명령 다섯이 들어 있다") {
                RegistrationFixture().registry.commands.map { it.id } shouldContainAll GRAPH_COMMAND_IDS
            }

            then("화면마다 이동 명령이 하나씩 생긴다") {
                val ids = RegistrationFixture().registry.commands.map { it.id }

                ids shouldContainAll AppDestination.entries.map { CommandId("navigate.${it.commandKey}") }
            }
        }

        `when`("저장소가 열려 있지 않으면") {
            then("저장소가 필요한 화면의 이동 명령이 막힌다") {
                val fixture = RegistrationFixture(active = ActiveRepository.None)

                val blocked = fixture.commandOf("navigate.blame")
                val open = fixture.commandOf("navigate.preferences")

                fixture.registry.commands.shouldContain(blocked)
                availabilityOfCommand(blocked).shouldBeInstanceOf<CommandAvailability.Blocked>()
                availabilityOfCommand(open) shouldBe CommandAvailability.Available
            }
        }

        `when`("선택으로 만들 수 있는 그래프 조작이 없으면") {
            then("그래프 명령이 목록에는 남되 막힌 상태다") {
                val fixture = RegistrationFixture(selected = null)

                availabilityOfCommand(fixture.commandOf("graph.cherryPick"))
                    .shouldBeInstanceOf<CommandAvailability.Blocked>()
            }
        }

        `when`("활성 탭이 경로를 잃었으면") {
            then("저장소를 바꾸는 명령이 전부 경로를 잃은 사유로 막힌다") {
                val selected = GraphOperation.CherryPick(CommitId.of(COMMIT), BranchTarget.Current)
                val fixture = RegistrationFixture(active = UNAVAILABLE, selected = selected)
                val reason = repositoryChangeBlockedReason(UNAVAILABLE)

                REPOSITORY_CHANGING_COMMAND_IDS.forEach { id ->
                    availabilityOfCommand(fixture.commandOf(id))
                        .shouldBeInstanceOf<CommandAvailability.Blocked>()
                        .reason shouldBe reason
                }
            }

            // 조용히 다른 저장소에 적용되는 것이 이 티켓이 막으려는 p0 다 — 열린 핸들은 직전 저장소다.
            then("막힌 명령은 실행되지 않는다 — 직전 저장소 핸들에 조작이 닿지 않는다") {
                val selected = GraphOperation.CherryPick(CommitId.of(COMMIT), BranchTarget.Current)
                val fixture = RegistrationFixture(active = UNAVAILABLE, selected = selected)

                fixture.commandOf("undo.last").execute().shouldBeInstanceOf<CommandOutcome.Blocked>()
                fixture.commandOf("rebase.openPlan").execute().shouldBeInstanceOf<CommandOutcome.Blocked>()
                fixture.commandOf("graph.cherryPick").execute().shouldBeInstanceOf<CommandOutcome.Blocked>()

                fixture.undoRequested shouldBe 0
                fixture.rebasePlanRequested shouldBe 0
                fixture.callbacks.lastRequested.shouldBeEmpty()
                fixture.dragDrop.confirmation.shouldBeNull()
            }

            then("탭을 닫고 팔레트를 여는 길은 막지 않는다 — 막으면 그 탭에 갇힌다") {
                val fixture = RegistrationFixture(active = UNAVAILABLE)

                ALWAYS_OPEN_COMMAND_IDS.forEach { id ->
                    availabilityOfCommand(fixture.commandOf(id)) shouldBe CommandAvailability.Available
                }
            }

            then("막힌 명령도 목록에는 남는다 — 왜 못 쓰는지 사용자가 알아야 한다") {
                val ids = RegistrationFixture(active = UNAVAILABLE).registry.commands.map { it.id.value }

                REPOSITORY_CHANGING_COMMAND_IDS.forEach { id -> ids shouldContain id }
                ids shouldNotContain "존재하지 않는 명령"
            }
        }

        `when`("저장소를 조작할 수 있으면") {
            then("차단 사유가 명령 자신의 판정을 덮지 않는다") {
                val selected = GraphOperation.CherryPick(CommitId.of(COMMIT), BranchTarget.Current)
                val fixture = RegistrationFixture(selected = selected)

                availabilityOfCommand(fixture.commandOf("graph.cherryPick")) shouldBe CommandAvailability.Available
                availabilityOfCommand(fixture.commandOf("undo.last")) shouldBe CommandAvailability.Available
                // 선택이 맞지 않는 명령은 **그 명령의 사유로** 막힌다 — 게이트가 덮어쓰지 않는다.
                availabilityOfCommand(fixture.commandOf("graph.merge"))
                    .shouldBeInstanceOf<CommandAvailability.Blocked>()
                    .reason shouldNotBe repositoryChangeBlockedReason(UNAVAILABLE)
            }
        }

        `when`("팔레트에서 그래프 조작을 실행하면") {
            then("배선이 화면에 넘기는 그 홀더에 확인창이 열린다") {
                val selected = GraphOperation.CherryPick(CommitId.of(COMMIT), BranchTarget.Current)
                val fixture = RegistrationFixture(selected = selected)
                fixture.dragDrop.confirmation.shouldBeNull()

                fixture.commandOf("graph.cherryPick").execute()
                    .shouldBeInstanceOf<CommandOutcome.Executed>()

                fixture.callbacks.lastRequested shouldBe listOf(selected)
                fixture.dragDrop.confirmation.shouldNotBeNull().choices shouldBe listOf(selected)
            }
        }
    }

    given("저장된 단축키 오버라이드") {

        `when`("시작 시 레지스트리에 적용하면") {
            then("저장 형식이 CommandId 매핑으로 변환돼 실효 단축키가 된다") {
                val fixture = RegistrationFixture()
                val stored = mapOf("refs.refresh" to bindingOf(Key.F5))

                val unapplied = fixture.registry.applyShortcutOverrides(stored.toShortcutOverrides())

                unapplied.shouldBeEmpty()
                fixture.registry.effectiveShortcutOf(fixture.commandOf("refs.refresh")).shouldNotBeNull()
                fixture.registry.commandFor(
                    Shortcut(Key.F5, setOf(ShortcutModifier.PRIMARY, ShortcutModifier.ALT)),
                )?.id shouldBe CommandId("refs.refresh")
            }
        }

        `when`("등록되지 않은 커맨드 id 가 섞여 있으면") {
            then("그 id 를 미적용으로 돌려준다") {
                val fixture = RegistrationFixture()
                val stored = mapOf("아직.없는.명령" to bindingOf(Key.F6))

                fixture.registry.applyShortcutOverrides(stored.toShortcutOverrides()) shouldBe
                    listOf(CommandId("아직.없는.명령"))
            }
        }

        `when`("두 명령이 같은 단축키로 저장돼 있으면") {
            then("나중 것을 묶지 않고 미적용으로 알린다") {
                val fixture = RegistrationFixture()
                val shared = bindingOf(Key.F7)
                val stored = mapOf("refs.refresh" to shared, "repository.close" to shared)

                val unapplied = fixture.registry.applyShortcutOverrides(stored.toShortcutOverrides())

                unapplied.size shouldBe 1
                fixture.registry.effectiveShortcutOf(fixture.commandOf(unapplied.single().value)).shouldBeNull()
            }
        }

        `when`("앱을 다시 켜 설정 파일에서 읽어 얹으면") {
            then("저장해 둔 단축키가 그대로 실효값이 된다") {
                val settingsFile = File(tempdir(), "settings.json").toPath()
                AppComponent(settingsFile, settingsFile.parent).updatePreferences.execute { stored ->
                    stored.copy(shortcutOverrides = mapOf("refs.refresh" to bindingOf(Key.F8)))
                }

                val fixture = RegistrationFixture()
                val stored = AppComponent(settingsFile, settingsFile.parent).loadPreferences.execute().shortcutOverrides

                fixture.registry.applyShortcutOverrides(stored.toShortcutOverrides()).shouldBeEmpty()
                fixture.registry.commandFor(
                    Shortcut(Key.F8, setOf(ShortcutModifier.PRIMARY, ShortcutModifier.ALT)),
                )?.id shouldBe CommandId("refs.refresh")
            }
        }

        `when`("오버라이드가 하나도 없으면") {
            then("명령이 들고 온 기본 단축키가 그대로 실효값이다") {
                val fixture = RegistrationFixture()

                fixture.registry.applyShortcutOverrides(emptyMap<String, ShortcutBinding>().toShortcutOverrides())
                    .shouldBeEmpty()
                fixture.registry.effectiveShortcutOf(fixture.commandOf("refs.refresh")) shouldBe
                    Shortcut(Key.R, setOf(ShortcutModifier.PRIMARY))
            }
        }
    }
})

/** 조건 판정만 꺼내 본다 — 실행 없이 가용성만 확인하려는 자리다. */
private fun availabilityOfCommand(command: Command): CommandAvailability = command.availability()
