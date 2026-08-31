package dev.undine.presentation.preferences

import dev.undine.application.identity.ApplyProfileUseCase
import dev.undine.application.identity.AssignedProfileNameUseCase
import dev.undine.application.identity.ClearLocalIdentityUseCase
import dev.undine.application.identity.DeleteProfileUseCase
import dev.undine.application.identity.IdentityUseCases
import dev.undine.application.identity.LoadProfilesUseCase
import dev.undine.application.identity.SaveProfileUseCase
import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.Commit
import dev.undine.domain.HistoryGateway
import dev.undine.domain.IdentityProfile
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.identity.IdentityGateway
import dev.undine.domain.identity.IdentityService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.IOException

private const val WORK_PROFILE = "회사"
private const val PERSONAL_PROFILE = "개인"
private const val WORK_EMAIL = "me@work.example"
private const val PERSONAL_EMAIL = "me@personal.example"
private const val SIGNING_KEY = "ABCD1234"

private val WORK = IdentityProfile(
    name = WORK_PROFILE,
    email = WORK_EMAIL,
    signingKeyId = SIGNING_KEY,
    defaultAuthentication = AuthenticationMethod.SSH,
    expectedHost = "github.com",
)

private val PERSONAL = IdentityProfile(
    name = PERSONAL_PROFILE,
    email = PERSONAL_EMAIL,
    signingKeyId = null,
    defaultAuthentication = AuthenticationMethod.HTTPS,
    expectedHost = null,
)

/**
 * 프로필 목록과 저장소 로컬 매핑을 메모리에 들고 있는 Gateway.
 *
 * 실패는 **호출 종류별로** 켠다 — 화면이 "무엇에 실패했는지" 를 구분해 다루는지 보려면 저장 실패와
 * 조회 실패를 따로 재현해야 한다. 저장소가 닫힌 상태는 실제 `GitAccess` 와 같은 `StateViolation` 이다.
 */
private class FakeIdentityGateway(
    initialProfiles: List<IdentityProfile> = emptyList(),
    private var repositoryOpen: Boolean = true,
) : IdentityGateway {

    var stored: List<IdentityProfile> = initialProfiles
        private set

    var assigned: String? = null
        private set

    var loadFailure: Exception? = null
    var deleteFailure: Exception? = null
    var applyFailure: Exception? = null
    var clearFailure: Exception? = null
    var assignedProfileNameFailure: Exception? = null

    /** 다음 저장 **한 번만** 실패시킨다 — 실패 뒤의 되돌리기가 성공할 수 있어야 그 경로를 볼 수 있다. */
    var nextSaveFailure: Exception? = null

    /** 다음 삭제 **한 번만** 실패시킨다 — 이름 변경 뒤의 보정 삭제가 성공하는지를 검증한다. */
    var nextDeleteFailure: Exception? = null

    /** 시작된 순서대로 쌓이는 호출 이름. 확인 전 삭제·중복 호출을 이 목록으로 판정한다. */
    val calls = mutableListOf<String>()

    override suspend fun profiles(): List<IdentityProfile> {
        calls += "profiles"
        loadFailure?.let { throw it }
        return stored
    }

    override suspend fun saveProfile(profile: IdentityProfile) {
        calls += "saveProfile:${profile.name}"
        nextSaveFailure?.let { failure ->
            nextSaveFailure = null
            throw failure
        }
        if (stored.any { saved -> saved.name == profile.name }) {
            throw UndineException.StateViolation("같은 이름의 신원 프로필이 이미 있습니다: '${profile.name}'")
        }
        stored = stored + profile
    }

    override suspend fun deleteProfile(name: String) {
        calls += "deleteProfile:$name"
        nextDeleteFailure?.let { failure ->
            nextDeleteFailure = null
            throw failure
        }
        deleteFailure?.let { throw it }
        stored = stored.filterNot { saved -> saved.name == name }
    }

    override suspend fun applyProfile(profile: IdentityProfile) {
        calls += "applyProfile:${profile.name}"
        applyFailure?.let { throw it }
        requireOpenRepository()
        assigned = profile.name
    }

    override suspend fun clearLocalIdentity() {
        calls += "clearLocalIdentity"
        clearFailure?.let { throw it }
        requireOpenRepository()
        assigned = null
    }

    override suspend fun assignedProfileName(): String? {
        calls += "assignedProfileName"
        assignedProfileNameFailure?.let { throw it }
        requireOpenRepository()
        return assigned
    }

    override suspend fun remoteHost(): String? = null

    private fun requireOpenRepository() {
        if (!repositoryOpen) throw UndineException.StateViolation("저장소가 열려 있지 않습니다")
    }
}

/** 커밋 전 검사만 쓰는 의존이라 이 화면에서는 호출되지 않는다. */
private object EmptyHistoryGateway : HistoryGateway {
    override suspend fun load(refs: List<RefName>, offset: Int, limit: Int): List<Commit> = emptyList()
}

private class AccountFixture(
    profiles: List<IdentityProfile> = emptyList(),
    repositoryOpen: Boolean = true,
) {
    val gateway = FakeIdentityGateway(profiles, repositoryOpen)
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    fun state(): AccountPreferencesState {
        val service = IdentityService(gateway, EmptyHistoryGateway)
        return AccountPreferencesState(
            scope = scope,
            identity = IdentityUseCases(
                loadProfiles = LoadProfilesUseCase(service),
                saveProfile = SaveProfileUseCase(service),
                deleteProfile = DeleteProfileUseCase(service),
                applyProfile = ApplyProfileUseCase(service),
                clearLocalIdentity = ClearLocalIdentityUseCase(service),
                assignedProfileName = AssignedProfileNameUseCase(gateway),
            ),
        ).also(AccountPreferencesState::refresh)
    }
}

private fun AccountPreferencesState.fillEditor(name: String, email: String, signingKeyId: String = "") {
    editName(name)
    editEmail(email)
    editSigningKeyId(signingKeyId)
}

/**
 * 계정 탭의 상태 홀더 — 목록 표시·추가·수정·삭제 확인·저장소 매핑.
 *
 * 보는 것은 **경계**다: 확인 전에는 지우지 않는가, 실패한 저장이 목록에 새 값으로 새지 않는가,
 * 저장소가 없을 때 매핑 부재를 실패로 뭉개지 않는가.
 */
class AccountPreferencesStateSpec : FunSpec({

    test("프로필 목록을 이름·이메일·서명 키와 함께 읽어 온다") {
        val state = AccountFixture(listOf(WORK, PERSONAL)).state()

        state.profiles shouldContainExactly listOf(WORK, PERSONAL)
        state.profiles.first().signingKeyId shouldBe SIGNING_KEY
        state.loadFailure shouldBe null
    }

    test("저장된 프로필이 없으면 빈 목록이고 실패가 아니다") {
        val state = AccountFixture().state()

        state.profiles.shouldBeEmpty()
        state.loadFailure shouldBe null
    }

    test("프로필을 추가하면 저장된 결과가 목록에 반영되고 편집기가 닫힌다") {
        val fixture = AccountFixture()
        val state = fixture.state()

        state.startAdd()
        state.fillEditor(name = WORK_PROFILE, email = WORK_EMAIL, signingKeyId = SIGNING_KEY)
        state.submitEditor()

        state.editor shouldBe null
        state.profiles.map(IdentityProfile::name) shouldContainExactly listOf(WORK_PROFILE)
        state.profiles.first().signingKeyId shouldBe SIGNING_KEY
        fixture.gateway.stored.map(IdentityProfile::name) shouldContainExactly listOf(WORK_PROFILE)
        state.saveFailure shouldBe null
    }

    test("이름이나 이메일이 비어 있으면 저장을 시작하지 않는다") {
        val fixture = AccountFixture()
        val state = fixture.state()

        state.startAdd()
        state.fillEditor(name = " ", email = WORK_EMAIL)

        state.canSubmitEditor shouldBe false
        state.submitEditor()

        state.editor shouldNotBe null
        fixture.gateway.stored.shouldBeEmpty()
    }

    test("저장에 실패하면 목록에 새 값이 나타나지 않고 편집기가 열린 채로 남는다") {
        val fixture = AccountFixture()
        val state = fixture.state()
        fixture.gateway.nextSaveFailure = IOException("설정 파일을 쓰지 못했습니다")

        state.startAdd()
        state.fillEditor(name = WORK_PROFILE, email = WORK_EMAIL)
        state.submitEditor()

        state.profiles.shouldBeEmpty()
        state.editor shouldBe AccountProfileEditor.Add
        state.draftName shouldBe WORK_PROFILE
        state.saveFailure.shouldNotBeNull()
    }

    test("같은 이름으로 프로필을 수정하면 새 값이 목록에 반영된다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()

        state.startEdit(WORK)
        state.fillEditor(name = WORK_PROFILE, email = PERSONAL_EMAIL, signingKeyId = "")
        state.submitEditor()

        state.profiles shouldContainExactly listOf(
            WORK.copy(email = PERSONAL_EMAIL, signingKeyId = null),
        )
        state.editor shouldBe null
    }

    test("이름을 바꿔 수정하면 새 이름만 남는다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()

        state.startEdit(WORK)
        state.fillEditor(name = PERSONAL_PROFILE, email = WORK_EMAIL, signingKeyId = SIGNING_KEY)
        state.submitEditor()

        state.profiles.map(IdentityProfile::name) shouldContainExactly listOf(PERSONAL_PROFILE)
    }

    test("이름 변경 중 원본 삭제가 실패하면 새 프로필을 보정 삭제한다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()
        fixture.gateway.nextDeleteFailure = IOException("원본 프로필을 지우지 못했습니다")

        state.startEdit(WORK)
        state.fillEditor(name = PERSONAL_PROFILE, email = PERSONAL_EMAIL)
        state.submitEditor()

        fixture.gateway.stored shouldContainExactly listOf(WORK)
        fixture.gateway.calls.takeLast(3) shouldContainExactly listOf(
            "saveProfile:$PERSONAL_PROFILE",
            "deleteProfile:$WORK_PROFILE",
            "deleteProfile:$PERSONAL_PROFILE",
        )
        state.editor.shouldNotBeNull()
        state.saveFailure.shouldNotBeNull()
    }

    test("수정 저장이 실패해도 지웠던 원본이 되살아난다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()

        state.startEdit(WORK)
        state.fillEditor(name = WORK_PROFILE, email = PERSONAL_EMAIL)
        fixture.gateway.nextSaveFailure = IOException("설정 파일을 쓰지 못했습니다")
        state.submitEditor()

        // 지운 뒤 넣지 못했는데 그대로 두면 사용자는 프로필을 잃는다.
        fixture.gateway.stored shouldContainExactly listOf(WORK)
        state.saveFailure.shouldNotBeNull()
    }

    test("삭제는 확인 요청만으로 실행되지 않는다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()

        state.requestDelete(WORK)

        state.pendingDeletion shouldBe WORK
        fixture.gateway.calls.none { call -> call.startsWith("deleteProfile") } shouldBe true
        fixture.gateway.stored shouldContainExactly listOf(WORK)
    }

    test("확인하면 삭제가 한 번만 실행되고 목록에서 사라진다") {
        val fixture = AccountFixture(listOf(WORK, PERSONAL))
        val state = fixture.state()

        state.requestDelete(WORK)
        state.confirmDelete()
        // 같은 확인을 두 번 눌러도 두 번 지우지 않는다.
        state.confirmDelete()

        fixture.gateway.calls.count { call -> call == "deleteProfile:$WORK_PROFILE" } shouldBe 1
        state.profiles shouldContainExactly listOf(PERSONAL)
        state.pendingDeletion shouldBe null
    }

    test("삭제에 실패하면 목록에서 사라지지 않고 사유가 표시된다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()
        fixture.gateway.deleteFailure = IOException("설정 파일을 쓰지 못했습니다")

        state.requestDelete(WORK)
        state.confirmDelete()

        state.profiles shouldContainExactly listOf(WORK)
        state.saveFailure.shouldNotBeNull()
    }

    test("삭제를 취소하면 프로필이 그대로 남는다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()

        state.requestDelete(WORK)
        state.cancelDelete()

        state.pendingDeletion shouldBe null
        fixture.gateway.calls.none { call -> call.startsWith("deleteProfile") } shouldBe true
        state.profiles shouldContainExactly listOf(WORK)
    }

    test("현재 저장소에 프로필을 지정하면 매핑이 반영된다") {
        val fixture = AccountFixture(listOf(WORK, PERSONAL))
        val state = fixture.state()

        state.assign(PERSONAL)

        state.assignedProfileName shouldBe PERSONAL_PROFILE
        state.isAssigned(PERSONAL) shouldBe true
        state.isAssigned(WORK) shouldBe false
        fixture.gateway.assigned shouldBe PERSONAL_PROFILE
    }

    test("지정을 해제하면 전역 Git 설정을 따른다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()
        state.assign(WORK)

        state.clearAssignment()

        state.assignedProfileName shouldBe null
        fixture.gateway.assigned shouldBe null
        fixture.gateway.calls.count { call -> call == "clearLocalIdentity" } shouldBe 1
    }

    test("지정 해제에 실패하면 기존 지정은 유지되고 실패가 표시된다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()
        state.assign(WORK)
        fixture.gateway.clearFailure = IOException("로컬 설정을 지우지 못했습니다")

        state.clearAssignment()

        state.assignedProfileName shouldBe WORK_PROFILE
        fixture.gateway.assigned shouldBe WORK_PROFILE
        state.saveFailure.shouldNotBeNull()
    }

    test("매핑 적용이 실패하면 지정이 바뀌지 않고 실패가 표시된다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()
        fixture.gateway.applyFailure = UndineException.GitOperationFailed(
            "identity.applyProfile",
            IOException("로컬 설정을 쓰지 못했습니다"),
        )

        state.assign(WORK)

        state.assignedProfileName shouldBe null
        state.saveFailure.shouldNotBeNull()
    }

    test("저장소가 열려 있지 않으면 매핑 부재를 실패로 알리지 않는다") {
        val state = AccountFixture(listOf(WORK), repositoryOpen = false).state()

        state.canAssignProfile shouldBe false
        state.assignedProfileName shouldBe null
        state.loadFailure shouldBe null
        state.profiles shouldContainExactly listOf(WORK)
    }

    test("목록을 읽지 못하면 앞서 읽은 목록을 유지하고 사유를 알린다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()
        fixture.gateway.loadFailure = IOException("설정 파일을 읽지 못했습니다")

        state.refresh()

        state.profiles shouldContainExactly listOf(WORK)
        state.loadFailure.shouldNotBeNull()
    }

    test("현재 저장소 매핑을 읽지 못하면 기존 지정은 유지하고 사유를 알린다") {
        val fixture = AccountFixture(listOf(WORK))
        val state = fixture.state()
        state.assign(WORK)
        fixture.gateway.assignedProfileNameFailure = IOException("로컬 설정을 읽지 못했습니다")

        state.refresh()

        state.assignedProfileName shouldBe WORK_PROFILE
        fixture.gateway.assigned shouldBe WORK_PROFILE
        state.loadFailure.shouldNotBeNull()
    }
})
