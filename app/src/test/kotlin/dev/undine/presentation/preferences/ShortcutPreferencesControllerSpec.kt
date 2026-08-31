package dev.undine.presentation.preferences

import androidx.compose.ui.input.key.Key
import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.presentation.palette.Command
import dev.undine.presentation.palette.CommandId
import dev.undine.presentation.palette.CommandRegistry
import dev.undine.presentation.palette.Shortcut
import dev.undine.presentation.palette.ShortcutModifier
import dev.undine.presentation.palette.ShortcutPlatform
import dev.undine.presentation.palette.primaryShortcut
import dev.undine.presentation.palette.testCommand
import dev.undine.presentation.palette.toBinding
import dev.undine.presentation.palette.toShortcutOverrides
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.IOException

private val SHORTCUT_REFRESH = CommandId("graph.refresh")
private val SHORTCUT_COMMIT = CommandId("staging.commit")
private val SHORTCUT_PALETTE = CommandId("palette.open")
private val SHORTCUT_UNREGISTERED = CommandId("not.registered")

private val SHORTCUT_CONTESTED = primaryShortcut(Key.R)

/** 저장된 값을 들고 있는 가짜 Gateway. 실패를 켜면 쓰기가 [IOException] 을 던진다. */
private class ShortcutSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    var saveFailure: IOException? = null

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        saveFailure?.let { throw it }
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        saveFailure?.let { throw it }
        stored = transform(stored)
    }
}

/**
 * 저장 경로와 레지스트리를 함께 세운 홀더.
 *
 * [settle] 은 화면의 `LaunchedEffect(state.settings)` 자리다 — 저장된 값이 바뀌었을 때만 행과
 * 실효 단축키가 다시 만들어진다는 규칙을 테스트도 같은 경로로 따른다.
 */
private class ShortcutFixture(
    overrides: Map<String, Shortcut> = emptyMap(),
    commands: List<Command> = listOf(
        testCommand(SHORTCUT_REFRESH.value, title = "새로 고침", shortcut = SHORTCUT_CONTESTED),
        testCommand(SHORTCUT_COMMIT.value, title = "커밋", shortcut = primaryShortcut(Key.Enter)),
        testCommand(SHORTCUT_PALETTE.value, title = "팔레트"),
    ),
) {
    val gateway = ShortcutSettingsGateway(
        Settings.DEFAULTS.copy(
            shortcutOverrides = overrides.mapValues { (_, shortcut) -> shortcut.toBinding() },
        ),
    )
    val registry = CommandRegistry(ShortcutPlatform.OTHER).apply { commands.forEach(::register) }

    val preferences = PreferencesState(
        scope = CoroutineScope(Dispatchers.Unconfined + Job()),
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
    )
    val controller = ShortcutPreferencesController(preferences, registry)

    init {
        preferences.refresh()
        settle()
    }

    fun settle() {
        controller.synchronize()
    }

    fun storedOverrides(): Map<String, Shortcut> =
        gateway.stored.shortcutOverrides.toShortcutOverrides().mapKeys { (id, _) -> id.value }

    fun rowOf(commandId: CommandId): ShortcutPreferencesRow = controller.rows.single { it.commandId == commandId }
}

/** 재시작을 흉내 낸다 — 같은 저장값을 새 레지스트리에 얹고 등록 순서만 바꿔 본다. */
private fun registryAfterRestart(
    overrides: Map<String, Shortcut>,
    commands: List<Command>,
): CommandRegistry = CommandRegistry(ShortcutPlatform.OTHER).apply {
    commands.forEach(::register)
    applyShortcutOverrides(overrides.mapValues { (_, shortcut) -> shortcut.toBinding() }.toShortcutOverrides())
}

/**
 * 단축키 탭의 재지정·충돌 해소·항목별 기본값 복원.
 *
 * Compose 렌더링 없이 상태 홀더만 검증한다 — 저장 규칙과 충돌 판정이 화면 구성과 무관하게
 * 성립해야 하고, 이 티켓이 잃었던 것(미등록 명령의 오버라이드)도 그 층에서 지켜진다.
 */
class ShortcutPreferencesControllerSpec : FunSpec({

    test("행은 등록 명령의 실효 단축키와 기본값·변경됨 여부를 함께 보여준다") {
        val fixture = ShortcutFixture(overrides = mapOf(SHORTCUT_COMMIT.value to Shortcut(Key.F6)))

        fixture.controller.rows.map { it.commandId } shouldContainExactly
            listOf(SHORTCUT_REFRESH, SHORTCUT_COMMIT, SHORTCUT_PALETTE)
        fixture.rowOf(SHORTCUT_REFRESH).shortcutLabel shouldBe "Ctrl+R"
        fixture.rowOf(SHORTCUT_REFRESH).isOverridden shouldBe false
        fixture.rowOf(SHORTCUT_COMMIT).shortcutLabel shouldBe "F6"
        fixture.rowOf(SHORTCUT_COMMIT).isOverridden shouldBe true
        // 기본 단축키가 없는 명령은 묶인 키가 없을 뿐 "해제" 상태가 아니다.
        fixture.rowOf(SHORTCUT_PALETTE).shortcutLabel.shouldBeNull()
        fixture.rowOf(SHORTCUT_PALETTE).isUnapplied shouldBe false
    }

    test("충돌하지 않는 키로 재지정하면 저장되고 실효 단축키가 바뀐다") {
        val fixture = ShortcutFixture()

        fixture.controller.requestRebind(SHORTCUT_REFRESH, Shortcut(Key.F5))

        fixture.controller.conflict.shouldBeNull()
        fixture.storedOverrides() shouldBe mapOf(SHORTCUT_REFRESH.value to Shortcut(Key.F5))
        fixture.settle()
        fixture.registry.commandFor(Shortcut(Key.F5))?.id shouldBe SHORTCUT_REFRESH
        fixture.registry.commandFor(SHORTCUT_CONTESTED).shouldBeNull()
        fixture.rowOf(SHORTCUT_REFRESH).shortcutLabel shouldBe "F5"
        fixture.rowOf(SHORTCUT_REFRESH).isOverridden shouldBe true
    }

    test("이미 쓰는 키를 지정하면 겹치는 명령을 알리고 확인 전에는 아무것도 바꾸지 않는다") {
        val fixture = ShortcutFixture()

        fixture.controller.requestRebind(SHORTCUT_COMMIT, SHORTCUT_CONTESTED)

        val conflict = fixture.controller.conflict.shouldNotBeNull()
        conflict.ownerId shouldBe SHORTCUT_REFRESH
        conflict.ownerTitle shouldBe "새로 고침"
        conflict.requested shouldBe SHORTCUT_CONTESTED
        fixture.gateway.stored.shortcutOverrides.shouldBeEmpty()
        fixture.settle()
        fixture.registry.commandFor(SHORTCUT_CONTESTED)?.id shouldBe SHORTCUT_REFRESH
    }

    test("충돌을 취소하면 저장된 오버라이드와 실효 단축키가 그대로다") {
        val fixture = ShortcutFixture()
        fixture.controller.requestRebind(SHORTCUT_COMMIT, SHORTCUT_CONTESTED)

        fixture.controller.cancelReplace()

        fixture.controller.conflict.shouldBeNull()
        fixture.gateway.stored.shortcutOverrides.shouldBeEmpty()
        fixture.settle()
        fixture.registry.commandFor(SHORTCUT_CONTESTED)?.id shouldBe SHORTCUT_REFRESH
    }

    test("교체를 확인하면 대상이 그 키를 갖고 상대는 자기 기본값으로 돌아간다") {
        val fixture = ShortcutFixture(overrides = mapOf(SHORTCUT_COMMIT.value to Shortcut(Key.F5)))
        fixture.controller.requestRebind(SHORTCUT_PALETTE, Shortcut(Key.F5))

        fixture.controller.confirmReplace()

        fixture.storedOverrides() shouldBe mapOf(SHORTCUT_PALETTE.value to Shortcut(Key.F5))
        fixture.settle()
        fixture.registry.commandFor(Shortcut(Key.F5))?.id shouldBe SHORTCUT_PALETTE
        // 키 이름 표기는 JVM 로케일을 타므로 단축키 값 자체로 본다.
        fixture.registry.commandFor(primaryShortcut(Key.Enter))?.id shouldBe SHORTCUT_COMMIT
        fixture.rowOf(SHORTCUT_COMMIT).isOverridden shouldBe false
        fixture.rowOf(SHORTCUT_COMMIT).isUnapplied shouldBe false
    }

    test("상대가 기본 단축키로 그 키를 쓰던 경우에도 재시작 후 대상이 그 키를 갖는다") {
        val refresh = testCommand(SHORTCUT_REFRESH.value, shortcut = SHORTCUT_CONTESTED)
        val commit = testCommand(SHORTCUT_COMMIT.value, shortcut = primaryShortcut(Key.Enter))
        val fixture = ShortcutFixture(commands = listOf(refresh, commit))
        fixture.controller.requestRebind(SHORTCUT_COMMIT, SHORTCUT_CONTESTED)

        fixture.controller.confirmReplace()

        val stored = fixture.storedOverrides()
        stored shouldBe mapOf(SHORTCUT_COMMIT.value to SHORTCUT_CONTESTED)
        // 등록 순서를 뒤집어도 결과가 같아야 한다 — 재시작마다 소유가 바뀌면 재지정이 뒤집힌다.
        listOf(listOf(refresh, commit), listOf(commit, refresh)).forEach { order ->
            val restarted = registryAfterRestart(stored, order)
            restarted.commandFor(SHORTCUT_CONTESTED)?.id shouldBe SHORTCUT_COMMIT
        }
    }

    test("미등록 명령이 같은 키의 오버라이드를 갖고 있어도 그 저장값은 지워지지 않는다") {
        val fixture = ShortcutFixture(overrides = mapOf(SHORTCUT_UNREGISTERED.value to SHORTCUT_CONTESTED))
        fixture.controller.requestRebind(SHORTCUT_COMMIT, SHORTCUT_CONTESTED)

        fixture.controller.confirmReplace()

        fixture.storedOverrides() shouldBe mapOf(
            SHORTCUT_UNREGISTERED.value to SHORTCUT_CONTESTED,
            SHORTCUT_COMMIT.value to SHORTCUT_CONTESTED,
        )
    }

    test("충돌 상대는 등록된 명령에서만 찾는다 — 미등록 오버라이드는 확인을 요구하지 않는다") {
        val fixture = ShortcutFixture(overrides = mapOf(SHORTCUT_UNREGISTERED.value to Shortcut(Key.F5)))

        fixture.controller.requestRebind(SHORTCUT_PALETTE, Shortcut(Key.F5))

        fixture.controller.conflict.shouldBeNull()
        fixture.storedOverrides() shouldBe mapOf(
            SHORTCUT_UNREGISTERED.value to Shortcut(Key.F5),
            SHORTCUT_PALETTE.value to Shortcut(Key.F5),
        )
    }

    test("항목별 기본값 복원은 그 명령만 되돌리고 다른 오버라이드는 남긴다") {
        val fixture = ShortcutFixture(
            overrides = mapOf(
                SHORTCUT_REFRESH.value to Shortcut(Key.F5),
                SHORTCUT_COMMIT.value to Shortcut(Key.F6),
                SHORTCUT_UNREGISTERED.value to Shortcut(Key.F7),
            ),
        )

        fixture.controller.restoreDefault(SHORTCUT_REFRESH)

        fixture.storedOverrides() shouldBe mapOf(
            SHORTCUT_COMMIT.value to Shortcut(Key.F6),
            SHORTCUT_UNREGISTERED.value to Shortcut(Key.F7),
        )
        fixture.settle()
        // 항목을 지우는 것은 "기본값 복귀" 지 "단축키 없음" 이 아니다.
        fixture.rowOf(SHORTCUT_REFRESH).shortcutLabel shouldBe "Ctrl+R"
        fixture.rowOf(SHORTCUT_REFRESH).isOverridden shouldBe false
    }

    test("저장에 실패하면 화면과 실효 단축키가 저장된 값에 머물고 사유를 알린다") {
        val fixture = ShortcutFixture()
        fixture.gateway.saveFailure = IOException("디스크 없음")

        fixture.controller.requestRebind(SHORTCUT_REFRESH, Shortcut(Key.F5))

        fixture.preferences.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.NotWritten>()
        fixture.gateway.stored.shortcutOverrides.shouldBeEmpty()
        fixture.settle()
        fixture.registry.commandFor(SHORTCUT_CONTESTED)?.id shouldBe SHORTCUT_REFRESH
        fixture.rowOf(SHORTCUT_REFRESH).shortcutLabel shouldBe "Ctrl+R"
        fixture.rowOf(SHORTCUT_REFRESH).isOverridden shouldBe false
    }

    test("묶지 못한 오버라이드는 경고 목록과 해당 항목 행에 남는다") {
        val fixture = ShortcutFixture(
            overrides = mapOf(
                SHORTCUT_COMMIT.value to SHORTCUT_CONTESTED,
                SHORTCUT_UNREGISTERED.value to Shortcut(Key.F5),
            ),
        )

        fixture.controller.unappliedCommandIds shouldContainExactlyInAnyOrder
            listOf(SHORTCUT_UNREGISTERED, SHORTCUT_REFRESH)
        fixture.rowOf(SHORTCUT_REFRESH).isUnapplied shouldBe true
        fixture.rowOf(SHORTCUT_REFRESH).valueIn(PREFERENCES_TEST_STRINGS) shouldBe
            PREFERENCES_TEST_STRINGS.shortcutApplyFailed
        fixture.rowOf(SHORTCUT_COMMIT).isUnapplied shouldBe false
        fixture.rowOf(SHORTCUT_COMMIT).shortcutLabel shouldBe "Ctrl+R"
    }

    test("등록되지 않은 미적용 id 도 경고 한 줄이 아니라 자기 행으로 남는다") {
        val fixture = ShortcutFixture(overrides = mapOf(SHORTCUT_UNREGISTERED.value to Shortcut(Key.F5)))

        fixture.controller.unappliedCommandIds shouldContainExactly listOf(SHORTCUT_UNREGISTERED)
        // 등록 명령 행 뒤에 붙는다 — 경고 영역에만 남으면 어느 항목이 미적용인지 목록에서 볼 수 없다.
        fixture.controller.rows.map { it.commandId } shouldContainExactly
            listOf(SHORTCUT_REFRESH, SHORTCUT_COMMIT, SHORTCUT_PALETTE, SHORTCUT_UNREGISTERED)
        val row = fixture.rowOf(SHORTCUT_UNREGISTERED)
        row.isUnapplied shouldBe true
        row.isRegistered shouldBe false
        // 이름을 줄 Command 가 없으므로 id 를 그대로 쓰고, 출처는 사용자 오버라이드다.
        row.title shouldBe SHORTCUT_UNREGISTERED.value
        row.valueIn(PREFERENCES_TEST_STRINGS) shouldBe PREFERENCES_TEST_STRINGS.shortcutApplyFailed
        row.sourceLabelIn(PREFERENCES_TEST_STRINGS) shouldBe PREFERENCES_TEST_STRINGS.shortcutOverridden
    }

    test("등록된 명령의 행은 등록 표시를 갖는다") {
        val fixture = ShortcutFixture()

        fixture.controller.rows.forAll { it.isRegistered shouldBe true }
    }

    test("수식키만 눌린 동안에는 캡처가 끝나지 않는다") {
        val fixture = ShortcutFixture()
        fixture.controller.startCapture(SHORTCUT_REFRESH)

        fixture.controller.capture(Shortcut(Key.CtrlLeft, setOf(ShortcutModifier.PRIMARY))) shouldBe false

        fixture.controller.capturingCommandId shouldBe SHORTCUT_REFRESH
        fixture.gateway.stored.shortcutOverrides.shouldBeEmpty()
    }

    test("Escape 는 저장하지 않고 캡처만 취소한다") {
        val fixture = ShortcutFixture()
        fixture.controller.startCapture(SHORTCUT_REFRESH)

        fixture.controller.capture(Shortcut(Key.Escape)) shouldBe true

        fixture.controller.capturingCommandId.shouldBeNull()
        fixture.gateway.stored.shortcutOverrides.shouldBeEmpty()
    }

    test("캡처한 조합은 재지정 요청으로 이어지고 캡처를 닫는다") {
        val fixture = ShortcutFixture()
        fixture.controller.startCapture(SHORTCUT_REFRESH)

        fixture.controller.capture(Shortcut(Key.F5, setOf(ShortcutModifier.SHIFT))) shouldBe true

        fixture.controller.capturingCommandId.shouldBeNull()
        fixture.storedOverrides() shouldBe
            mapOf(SHORTCUT_REFRESH.value to Shortcut(Key.F5, setOf(ShortcutModifier.SHIFT)))
    }

    test("캡처 중이 아니면 키 입력을 소비하지 않는다") {
        val fixture = ShortcutFixture()

        fixture.controller.capture(Shortcut(Key.F5)) shouldBe false

        fixture.gateway.stored.shortcutOverrides.shouldBeEmpty()
    }

    test("행 출처는 기본값과 변경됨을 기존 문구로 가른다") {
        val fixture = ShortcutFixture(overrides = mapOf(SHORTCUT_COMMIT.value to Shortcut(Key.F6)))

        fixture.rowOf(SHORTCUT_REFRESH).sourceLabelIn(PREFERENCES_TEST_STRINGS) shouldBe
            PREFERENCES_TEST_STRINGS.shortcutDefault
        fixture.rowOf(SHORTCUT_COMMIT).sourceLabelIn(PREFERENCES_TEST_STRINGS) shouldBe
            PREFERENCES_TEST_STRINGS.shortcutOverridden
    }
})
