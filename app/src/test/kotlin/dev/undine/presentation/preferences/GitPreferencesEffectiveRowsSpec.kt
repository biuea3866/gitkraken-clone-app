package dev.undine.presentation.preferences

import dev.undine.application.gitconfig.ReadEffectiveConfigUseCase
import dev.undine.domain.PullStrategy
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.SettingsPreference
import dev.undine.domain.UndineException
import dev.undine.domain.gitconfig.EffectiveValue
import dev.undine.domain.gitconfig.GitConfigGateway
import dev.undine.domain.gitconfig.GitConfigKey
import dev.undine.domain.gitconfig.GitConfigSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

private val TEXTS = PREFERENCES_TEST_STRINGS

private val STORED = Settings.DEFAULTS.copy(
    defaultBranchName = "trunk",
    pullStrategy = PullStrategy.MERGE,
)

private val REPOSITORY = RepositoryPath("/tmp/undine")

private val OTHER_REPOSITORY = RepositoryPath("/tmp/undine-other")

private val READ_FAILURE =
    UndineException.GitOperationFailed("read git config", IllegalStateException("손상된 설정 파일"))

/** git 세 범위를 **읽어 봤고** 값이 없었던 결과. 아직 안 읽은 것·읽지 못한 것과 구분된다. */
private val NO_GIT_VALUES = GitEffectiveConfig.Loaded(emptyMap())

/** git 설정을 읽지 못한 결과. 값이 있는지 없는지조차 모른다. */
private val UNREADABLE = GitEffectiveConfig.Failed(emptyMap(), READ_FAILURE)

/**
 * 정해 둔 실효값만 돌려주는 Gateway. 실제 설정 파일 우선순위는 `GitConfigGatewayImplSpec` 이
 * 진짜 JGit 파서로 검증하므로, 여기서는 **화면이 그 결과를 어떻게 결합하는지**만 본다.
 *
 * 실패는 [failsFromCall] 번째 조회부터 일어난다 — 같은 홀더에서 **성공한 뒤 실패**하는 전이를
 * 봐야 앞서 읽은 값을 지우는 회귀가 잡힌다. 인스턴스를 새로 만들어 비교하면 그 경로가 열리지 않는다.
 */
private class FakeGitConfigGateway(
    private val values: Map<GitConfigKey, EffectiveValue> = emptyMap(),
    private val failure: UndineException? = null,
    private val failsFromCall: Int = 0,
) : GitConfigGateway {

    var requestedRepository: RepositoryPath? = null
        private set

    var calls: Int = 0
        private set

    /** 걸어 두면 조회가 끝나지 않는다 — 화면이 **조회 중** 상태를 어떻게 다루는지 볼 수 있다. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun effectiveValues(repository: RepositoryPath?): Map<GitConfigKey, EffectiveValue> {
        requestedRepository = repository
        val failing = failure?.takeIf { calls >= failsFromCall }
        calls += 1
        gate?.await()
        failing?.let { throw it }
        return values
    }
}

/** 읽기에 성공한 조회 결과. */
private fun readValues(vararg entries: Pair<GitConfigKey, EffectiveValue>): GitEffectiveConfig =
    GitEffectiveConfig.Loaded(entries.toMap())

private fun effectiveStateOf(
    gateway: FakeGitConfigGateway,
    repository: RepositoryPath? = REPOSITORY,
): GitPreferencesEffectiveState = GitPreferencesEffectiveState(
    scope = CoroutineScope(Dispatchers.Unconfined + Job()),
    readEffectiveConfig = ReadEffectiveConfigUseCase(gateway),
    repository = repository,
).also(GitPreferencesEffectiveState::refresh)

/**
 * Git 탭의 **앱 설정 ↔ git 설정 결합**.
 *
 * 보는 것은 경계다: git 값이 있으면 그 값과 범위를 말하는가, 세 범위 모두에 없을 때만 앱 값으로
 * 떨어지는가, 읽기 실패를 "설정 안 함" 으로 접지 않는가.
 */
class GitPreferencesEffectiveRowsSpec : FunSpec({

    test("git 세 범위 어디에도 값이 없으면 앱 설정 값을 앱 출처로 보여 준다") {
        val rows = listOf(
            effectiveDefaultBranchNameRow(STORED, NO_GIT_VALUES, TEXTS),
            effectivePullStrategyRow(STORED, NO_GIT_VALUES, TEXTS),
        )

        rows.map { it.source }.distinct() shouldContainExactly listOf(PreferenceValueSource.APP_SETTINGS)
        rows.map { it.sourceLabel }.distinct() shouldContainExactly listOf(TEXTS.sourceApp)
        rows.map { it.value } shouldContainExactly listOf("trunk", TEXTS.pullStrategyMerge)
        // 앱이 값의 주인이라 항목별 기본값 복원을 그대로 내준다.
        rows.mapNotNull { it.restorablePreference } shouldContainExactly listOf(
            SettingsPreference.DEFAULT_BRANCH_NAME,
            SettingsPreference.PULL_STRATEGY,
        )
    }

    test("저장소와 전역에 모두 있으면 저장소 값을 저장소 출처로 보여 준다") {
        // 게이트웨이가 우선순위대로 하나를 고르고 그 범위를 실어 준다 — 화면은 고른 값을 그대로 쓴다.
        val row = effectiveDefaultBranchNameRow(
            STORED,
            readValues(GitConfigKey.INIT_DEFAULT_BRANCH to EffectiveValue("develop", GitConfigSource.REPOSITORY)),
            TEXTS,
        )

        row.value shouldBe "develop"
        row.source shouldBe PreferenceValueSource.GIT_CONFIG
        row.sourceLabel shouldBe TEXTS.sourceGitRepository
        // git 이 이기는 동안 되돌릴 값이 없다 — 눌러도 보이는 값이 안 바뀌는 버튼을 내주지 않는다.
        row.canRestoreDefault shouldBe false
    }

    test("전역에만 있으면 전역 출처로, 시스템에만 있으면 시스템 출처로 구분해 보여 준다") {
        val global = effectiveDefaultBranchNameRow(
            STORED,
            readValues(GitConfigKey.INIT_DEFAULT_BRANCH to EffectiveValue("main", GitConfigSource.GLOBAL)),
            TEXTS,
        )
        val system = effectiveDefaultBranchNameRow(
            STORED,
            readValues(GitConfigKey.INIT_DEFAULT_BRANCH to EffectiveValue("master", GitConfigSource.SYSTEM)),
            TEXTS,
        )

        global.sourceLabel shouldBe TEXTS.sourceGitGlobal
        system.sourceLabel shouldBe TEXTS.sourceGitSystem
        // 셋이 서로 다른 문구여야 어느 파일을 고쳐야 하는지 알 수 있다.
        listOf(TEXTS.sourceGitRepository, global.sourceLabel, system.sourceLabel).distinct().size shouldBe 3
    }

    test("pull.rebase 는 domain 의 Git 철자 해석을 거쳐 방식 문구가 된다") {
        listOf("true", "yes", "on", "1").forEach { spelling ->
            effectivePullStrategyRow(
                STORED,
                readValues(GitConfigKey.PULL_REBASE to EffectiveValue(spelling, GitConfigSource.GLOBAL)),
                TEXTS,
            ).value shouldBe TEXTS.pullStrategyRebase
        }
        listOf("false", "no", "off", "0").forEach { spelling ->
            effectivePullStrategyRow(
                STORED,
                readValues(GitConfigKey.PULL_REBASE to EffectiveValue(spelling, GitConfigSource.GLOBAL)),
                TEXTS,
            ).value shouldBe TEXTS.pullStrategyMerge
        }
    }

    test("읽을 수 없는 철자는 단정하지 않고 적힌 값을 그대로 보여 준다") {
        val row = effectivePullStrategyRow(
            STORED,
            readValues(GitConfigKey.PULL_REBASE to EffectiveValue("interactive", GitConfigSource.REPOSITORY)),
            TEXTS,
        )

        // merge 로 단정하면 사용자가 반대로 안다 — 판단 불가는 판단 생략이다.
        row.value shouldBe "interactive"
        row.sourceLabel shouldBe TEXTS.sourceGitRepository
    }

    test("빈 값도 설정된 값이다 — 부재로 접어 앱 설정으로 떨어지지 않는다") {
        val row = effectiveDefaultBranchNameRow(
            STORED,
            readValues(GitConfigKey.INIT_DEFAULT_BRANCH to EffectiveValue("", GitConfigSource.REPOSITORY)),
            TEXTS,
        )

        row.value shouldBe ""
        row.source shouldBe PreferenceValueSource.GIT_CONFIG
    }

    test("한 키만 git 에 있으면 다른 행은 앱 설정에 머문다") {
        val effective = readValues(
            GitConfigKey.PULL_REBASE to EffectiveValue("true", GitConfigSource.SYSTEM),
        )

        effectivePullStrategyRow(STORED, effective, TEXTS).source shouldBe PreferenceValueSource.GIT_CONFIG
        effectiveDefaultBranchNameRow(STORED, effective, TEXTS).source shouldBe
            PreferenceValueSource.APP_SETTINGS
    }

    test("상태 홀더는 읽은 실효값을 그대로 싣고 저장소 범위를 함께 넘긴다") {
        val gateway = FakeGitConfigGateway(
            mapOf(GitConfigKey.USER_EMAIL to EffectiveValue("me@undine.dev", GitConfigSource.GLOBAL)),
        )

        val state = effectiveStateOf(gateway)

        state.effective shouldBe GitEffectiveConfig.Loaded(
            mapOf(GitConfigKey.USER_EMAIL to EffectiveValue("me@undine.dev", GitConfigSource.GLOBAL)),
        )
        gateway.requestedRepository shouldBe REPOSITORY
    }

    test("저장소가 열려 있지 않아도 조회한다 — 전역·시스템만 보는 정상 경로다") {
        val gateway = FakeGitConfigGateway()

        val state = effectiveStateOf(gateway, repository = null)

        gateway.calls shouldBe 1
        gateway.requestedRepository.shouldBeNull()
        // 빈 결과지만 **읽어 본** 결과다 — 그래야 행이 앱 출처를 말할 근거가 생긴다.
        state.effective shouldBe GitEffectiveConfig.Loaded(emptyMap())
    }

    test("설정 파일을 읽지 못하면 사유가 남고 부재로 접히지 않는다") {
        val gateway = FakeGitConfigGateway(failure = READ_FAILURE)

        val state = effectiveStateOf(gateway)

        // 실패를 빈 결과로 접으면 손상된 설정이 "설정 안 함" 으로 보이고 앱 값이 실효값 행세를 한다.
        state.effective shouldBe GitEffectiveConfig.Failed(emptyMap(), READ_FAILURE)
    }

    test("아직 읽지 않았으면 앱 설정 값도 앱 출처도 내놓지 않는다") {
        val rows = listOf(
            effectiveDefaultBranchNameRow(STORED, GitEffectiveConfig.Unread, TEXTS),
            effectivePullStrategyRow(STORED, GitEffectiveConfig.Unread, TEXTS),
        )

        // APP 출처는 "git 세 범위 어디에도 없음을 확인했다" 는 주장이다 — 읽기 전에 하면 거짓말이다.
        rows.map { it.source }.distinct() shouldContainExactly listOf(PreferenceValueSource.PENDING)
        rows.map { it.sourceLabel }.distinct() shouldContainExactly listOf(TEXTS.sourcePending)
        // 앱 설정 값으로 대신 채우지도 않는다 — 최초 렌더에 스쳐도 사용자는 그것을 실효값으로 읽는다.
        rows.map { it.value }.distinct() shouldContainExactly listOf("")
    }

    test("최초 상태는 미조회다 — 조회 전에 빈 결과와 같은 값이 되지 않는다") {
        val state = GitPreferencesEffectiveState(
            scope = CoroutineScope(Dispatchers.Unconfined + Job()),
            readEffectiveConfig = ReadEffectiveConfigUseCase(FakeGitConfigGateway()),
            repository = REPOSITORY,
        )

        state.effective shouldBe GitEffectiveConfig.Unread
    }

    test("조회 중에는 진행 중으로 두고 앱 출처로 떨어지지 않는다") {
        val gateway = FakeGitConfigGateway()
        val release = CompletableDeferred<Unit>()
        gateway.gate = release

        val state = effectiveStateOf(gateway)

        state.effective shouldBe GitEffectiveConfig.Loading(emptyMap())
        effectiveDefaultBranchNameRow(STORED, state.effective, TEXTS).source shouldBe
            PreferenceValueSource.PENDING

        release.complete(Unit)

        // 다 읽고 나서야 "git 에 없음" 이 확정되고 앱 출처를 말할 수 있다.
        state.effective shouldBe GitEffectiveConfig.Loaded(emptyMap())
        effectiveDefaultBranchNameRow(STORED, state.effective, TEXTS).source shouldBe
            PreferenceValueSource.APP_SETTINGS
    }

    test("다시 읽는 동안에도 읽어 둔 git 값은 그대로 보여 준다") {
        val values = mapOf(GitConfigKey.INIT_DEFAULT_BRANCH to EffectiveValue("develop", GitConfigSource.GLOBAL))
        val gateway = FakeGitConfigGateway(values)
        val state = effectiveStateOf(gateway)
        val release = CompletableDeferred<Unit>()
        gateway.gate = release

        state.refresh()

        // 조회 중이라고 화면을 비우면 사용자는 git 설정이 사라진 줄 안다.
        state.effective shouldBe GitEffectiveConfig.Loading(values)
        effectiveDefaultBranchNameRow(STORED, state.effective, TEXTS).value shouldBe "develop"
        release.complete(Unit)
    }

    test("읽지 못한 동안에는 앱 설정 값을 실효값이라고 말하지 않는다") {
        val rows = listOf(
            effectiveDefaultBranchNameRow(STORED, UNREADABLE, TEXTS),
            effectivePullStrategyRow(STORED, UNREADABLE, TEXTS),
        )

        // 값이 안 올라온 이유가 "git 에 없음" 인지 "못 읽음" 인지 모르는 채 앱 출처라고 하면 거짓말이다.
        rows.map { it.source }.distinct() shouldContainExactly listOf(PreferenceValueSource.UNVERIFIED)
        rows.map { it.sourceLabel }.distinct() shouldContainExactly listOf(TEXTS.sourceUnverified)
        // 보여 줄 값은 여전히 앱 설정 값이고, 그 값은 사용자의 것이라 되돌리기도 그대로 내준다.
        rows.map { it.value } shouldContainExactly listOf("trunk", TEXTS.pullStrategyMerge)
        rows.mapNotNull { it.restorablePreference } shouldContainExactly listOf(
            SettingsPreference.DEFAULT_BRANCH_NAME,
            SettingsPreference.PULL_STRATEGY,
        )
    }

    test("읽지 못했어도 이미 읽어 둔 키는 git 값과 범위를 그대로 보여 준다") {
        val partiallyRead = GitEffectiveConfig.Failed(
            mapOf(GitConfigKey.PULL_REBASE to EffectiveValue("true", GitConfigSource.GLOBAL)),
            READ_FAILURE,
        )

        effectivePullStrategyRow(STORED, partiallyRead, TEXTS).sourceLabel shouldBe TEXTS.sourceGitGlobal
        // 값이 올라온 적 없는 키만 "확인 못 함" 이다 — 아는 것까지 모른다고 하지 않는다.
        effectiveDefaultBranchNameRow(STORED, partiallyRead, TEXTS).source shouldBe
            PreferenceValueSource.UNVERIFIED
    }

    test("성공한 뒤 다시 읽다 실패하면 읽어 둔 값은 남고 사유가 붙는다") {
        val values = mapOf(GitConfigKey.INIT_DEFAULT_BRANCH to EffectiveValue("develop", GitConfigSource.GLOBAL))
        val gateway = FakeGitConfigGateway(values, failure = READ_FAILURE, failsFromCall = 1)
        val state = effectiveStateOf(gateway)
        state.effective.shouldBeInstanceOf<GitEffectiveConfig.Loaded>()

        state.refresh()

        // 값을 비우면 사용자는 git 설정이 사라진 줄 알고, 사유를 빼면 못 읽은 것이 부재로 보인다.
        state.effective shouldBe GitEffectiveConfig.Failed(values, READ_FAILURE)
        gateway.calls shouldBe 2
    }

    test("저장소를 바꾼 뒤 첫 조회가 실패하면 값 없이 사유만 남는다") {
        val gateway = FakeGitConfigGateway(failure = READ_FAILURE)

        val state = effectiveStateOf(gateway, repository = OTHER_REPOSITORY)

        gateway.requestedRepository shouldBe OTHER_REPOSITORY
        state.effective shouldBe GitEffectiveConfig.Failed(emptyMap(), READ_FAILURE)
    }
})
