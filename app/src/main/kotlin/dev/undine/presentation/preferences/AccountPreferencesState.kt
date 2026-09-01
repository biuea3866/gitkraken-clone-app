package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.identity.IdentityUseCases
import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.IdentityProfile
import dev.undine.domain.UndineException
import dev.undine.presentation.i18n.PreferencesStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 프로필 편집기가 열려 있는 대상. 닫혀 있으면 `null` 이다.
 *
 * 추가와 수정을 한 편집기로 다루되 **고칠 원본이 있는지**가 갈린다 — 수정은 [Edit.original] 의
 * 이름을 원본 키로 삼아 원자적 수정 한 번으로 끝난다. 되돌릴 중간 상태가 생기지 않는다.
 */
sealed interface AccountProfileEditor {

    /** 새 프로필. 되돌릴 원본이 없다. */
    data object Add : AccountProfileEditor

    /** 이미 저장된 프로필을 고친다. */
    data class Edit(val original: IdentityProfile) : AccountProfileEditor
}

/** 편집기 제목. 문자열은 리소스에서만 온다. */
fun AccountProfileEditor.titleIn(texts: PreferencesStrings): String = when (this) {
    AccountProfileEditor.Add -> texts.profileAdd
    is AccountProfileEditor.Edit -> texts.profileEdit
}

/**
 * 계정 탭의 상태 홀더 (compose-ui 규칙 1). [IdentityUseCases] 만 호출하고 Gateway 를 알지 못한다.
 *
 * **화면은 성공한 저장·읽기 결과로만 갱신한다.** 새 값을 먼저 그려 두면 저장이 실패했을 때 무엇으로
 * 되돌릴지가 문제가 되고, 목록은 프로필 삭제까지 다루므로 그 어긋남이 곧 "지운 줄 알았는데 남아
 * 있음" 이 된다. 그래서 모든 변경은 성공한 뒤에 목록을 다시 읽어 반영한다.
 *
 * **변경은 취소로 중간에 끊기지 않는다.** 이 홀더의 scope 는 컴포지션 수명이라 탭을 옮기거나 창을
 * 닫으면 취소되는데, 지우기·적용이 반쯤 끝난 채 멈추면 설정 파일과 저장소 로컬 설정이 갈린다.
 * 변경 자체는 [NonCancellable] 로 감싸 시작한 것을 끝내고, 그 뒤의 다시 읽기만 취소를 따른다.
 *
 * **프로필 변경을 Git Undo 스택에 기록하지 않는다.** Git 되돌리기와 설정 되돌리기는 다른 개념이라
 * 한 스택에 섞으면 Undo 를 눌렀을 때 무엇이 되돌아갈지 예측할 수 없다. 삭제의 되돌리기는 확인
 * 게이트([requestDelete] → [confirmDelete])가 대신한다.
 *
 * **이메일 형식 검증과 이름 변경 거부는 이 화면의 몫이 아니다** — 둘 다 domain 계약이 사유와 함께
 * 거부하고, 이 홀더는 그 거부를 [saveFailure] 로 옮기기만 한다. 화면이 자기 검증을 만들면 규칙이
 * 두 벌이 되어 한쪽만 고쳐진다.
 */
@Stable
@Suppress("TooManyFunctions") // 목록·편집기·삭제 확인·저장소 매핑이 한 탭의 상태 전이다.
class AccountPreferencesState(
    private val scope: CoroutineScope,
    private val identity: IdentityUseCases,
) {
    /** 저장된 프로필 전체. 한 건도 없으면 빈 목록이다(정상 상태다). */
    var profiles: List<IdentityProfile> by mutableStateOf(emptyList())
        private set

    /** 현재 저장소에 지정된 프로필 이름. 지정된 적이 없으면 `null`. */
    var assignedProfileName: String? by mutableStateOf(null)
        private set

    /**
     * 저장소가 열려 있어 매핑을 지정·해제할 수 있는가.
     *
     * 저장소 없이 설정 창을 열 수 있고 그때 매핑은 **없는 것이지 실패한 것이 아니다** — 실패로
     * 알리면 사용자가 고칠 것이 없는 오류를 본다.
     */
    var canAssignProfile: Boolean by mutableStateOf(false)
        private set

    /** 열려 있는 편집기. `null` 이면 목록만 보인다. */
    var editor: AccountProfileEditor? by mutableStateOf(null)
        private set

    var draftName: String by mutableStateOf("")
        private set

    var draftEmail: String by mutableStateOf("")
        private set

    var draftSigningKeyId: String by mutableStateOf("")
        private set

    /** 확인을 기다리는 삭제 대상. 확인 전에는 아무것도 지우지 않는다. */
    var pendingDeletion: IdentityProfile? by mutableStateOf(null)
        private set

    /** 프로필·매핑을 읽지 못한 사유. `null` 이면 저장된 값을 그대로 읽었다. */
    var loadFailure: Exception? by mutableStateOf(null)
        private set

    /** 마지막 저장·삭제·매핑이 반영되지 못한 사유. 다음 변경이 성공하면 지워진다. */
    var saveFailure: Exception? by mutableStateOf(null)
        private set

    /** 이 홀더가 마지막으로 줄 세운 작업. 다음 작업은 이것이 끝난 뒤에 시작한다. */
    private var lastWork: Job = Job().apply { complete() }

    /** 편집기를 저장으로 닫을 수 있는가. 이름과 이메일이 비면 저장을 시작하지 않는다. */
    val canSubmitEditor: Boolean
        get() = editor != null && draftName.isNotBlank() && draftEmail.isNotBlank()

    /** [profile] 이 지금 이 저장소에 지정된 프로필인가. */
    fun isAssigned(profile: IdentityProfile): Boolean = profile.name == assignedProfileName

    /** 프로필 목록과 현재 저장소의 매핑을 다시 읽는다. 탭 진입 시 배선이 호출한다. */
    fun refresh() {
        enqueue { reload() }
    }

    fun startAdd() {
        openEditor(AccountProfileEditor.Add, name = "", email = "", signingKeyId = "")
    }

    fun startEdit(profile: IdentityProfile) {
        openEditor(
            target = AccountProfileEditor.Edit(profile),
            name = profile.name,
            email = profile.email,
            signingKeyId = profile.signingKeyId.orEmpty(),
        )
    }

    fun cancelEditor() {
        closeEditor()
    }

    fun editName(value: String) {
        draftName = value
    }

    fun editEmail(value: String) {
        draftEmail = value
    }

    fun editSigningKeyId(value: String) {
        draftSigningKeyId = value
    }

    /**
     * 편집 중인 값을 저장한다. 성공한 뒤에야 편집기를 닫고 목록을 다시 읽는다 — 실패하면 입력이
     * 그대로 남아 사용자가 고쳐 다시 시도할 수 있다.
     */
    fun submitEditor() {
        val target = editor ?: return
        if (!canSubmitEditor) return
        val edited = draftProfile(target)
        enqueue {
            val stored = mutate { save(target, edited) }
            if (stored) {
                closeEditor()
                reload()
            }
        }
    }

    /** 삭제 확인을 띄운다. **이 호출만으로는 지우지 않는다** — 되돌릴 수 없는 연산이다. */
    fun requestDelete(profile: IdentityProfile) {
        pendingDeletion = profile
    }

    fun cancelDelete() {
        pendingDeletion = null
    }

    /** 확인을 받은 삭제. 대상을 먼저 비워 같은 확인이 두 번 지우지 않게 한다. */
    fun confirmDelete() {
        val target = pendingDeletion ?: return
        pendingDeletion = null
        enqueue {
            if (mutate { identity.deleteProfile.execute(target.name) }) reload()
        }
    }

    /** 현재 저장소의 로컬 Git 설정에 [profile] 을 지정한다. 전역 설정은 바뀌지 않는다. */
    fun assign(profile: IdentityProfile) {
        enqueue {
            if (mutate { identity.applyProfile.execute(profile) }) reload()
        }
    }

    /** 지정을 해제해 전역 Git 설정을 따르게 한다 — 지정의 롤백이다. */
    fun clearAssignment() {
        enqueue {
            if (mutate { identity.clearLocalIdentity.execute() }) reload()
        }
    }

    private fun openEditor(target: AccountProfileEditor, name: String, email: String, signingKeyId: String) {
        editor = target
        draftName = name
        draftEmail = email
        draftSigningKeyId = signingKeyId
    }

    private fun closeEditor() {
        editor = null
        draftName = ""
        draftEmail = ""
        draftSigningKeyId = ""
    }

    /**
     * 편집 중인 값으로 만든 프로필.
     *
     * 인증 방식과 예상 호스트는 이 탭이 편집하지 않는다 — 수정은 원본 값을 그대로 잇고, 추가는
     * 프로필 기반 작업에서 기본이 되는 SSH 로 시작한다. 예상 호스트가 없는 프로필은 호스트 경고
     * 대상이 아니다(경고 없음이지 실패가 아니다).
     */
    private fun draftProfile(target: AccountProfileEditor): IdentityProfile {
        val original = (target as? AccountProfileEditor.Edit)?.original
        return IdentityProfile(
            name = draftName.trim(),
            email = draftEmail.trim(),
            signingKeyId = draftSigningKeyId.trim().takeIf(String::isNotEmpty),
            defaultAuthentication = original?.defaultAuthentication ?: AuthenticationMethod.SSH,
            expectedHost = original?.expectedHost,
        )
    }

    /**
     * 편집 결과를 저장한다. 수정은 **원자적 갱신 한 번**이다 —
     * [dev.undine.application.identity.UpdateProfileUseCase] 가 읽기-수정-쓰기를 한 임계구역 안에서
     * 끝내므로, 지운 뒤 넣다 실패해 프로필을 잃는 경로도 그것을 되돌리는 보상 경로도 없다
     * (결정 G34 UND-76 4).
     *
     * **이름 변경은 여기서 판정하지 않는다.** 원본 이름을 그대로 넘기고, 이름이 다르면 계약이
     * 사유와 함께 거부한다 (결정 G38) — 화면이 자기 검증을 만들면 domain 과 두 벌이 된다.
     */
    private suspend fun save(target: AccountProfileEditor, edited: IdentityProfile) = when (target) {
        AccountProfileEditor.Add -> identity.saveProfile.execute(edited)
        is AccountProfileEditor.Edit -> identity.updateProfile.execute(target.original.name, edited)
    }

    /**
     * 변경 한 건을 수행하고 성공 여부를 돌려준다. 성공했을 때만 호출부가 목록을 다시 읽는다.
     *
     * [NonCancellable] 이 덮는 것은 **시작한 변경을 끝내는 구간**뿐이다 — 그 뒤의 다시 읽기는
     * 취소를 따라도 파일과 화면이 갈리지 않는다(화면 자체가 사라진 뒤다).
     */
    private suspend fun mutate(change: suspend () -> Unit): Boolean =
        try {
            withContext(NonCancellable) { change() }
            saveFailure = null
            true
        } catch (failure: UndineException) {
            saveFailure = failure
            false
        } catch (failure: IOException) {
            saveFailure = failure
            false
        }

    /**
     * 프로필 목록과 매핑을 다시 읽는다. **읽은 값만 화면에 쓴다** — 실패한 조회로 목록을 비우면
     * 사용자는 프로필이 사라진 줄 안다.
     */
    private suspend fun reload() {
        try {
            profiles = identity.loadProfiles.execute()
            loadFailure = null
        } catch (failure: UndineException) {
            loadFailure = failure
        } catch (failure: IOException) {
            loadFailure = failure
        }
        reloadAssignment()
    }

    /**
     * 현재 저장소의 매핑을 읽는다. 저장소가 열려 있지 않으면 매핑을 **낼 수 없는 상태**로 두고
     * 실패로 알리지 않는다 — 그 경우 사용자가 고칠 것이 없다.
     */
    // 열려 있지 않다는 **사실 자체가 결과**다 — 사유를 화면에 남기면 사용자가 고칠 것 없는 오류가 뜬다.
    @Suppress("SwallowedException")
    private suspend fun reloadAssignment() {
        try {
            assignedProfileName = identity.assignedProfileName.execute()
            canAssignProfile = true
        } catch (closed: UndineException.StateViolation) {
            assignedProfileName = null
            canAssignProfile = false
        } catch (failure: UndineException) {
            loadFailure = failure
        } catch (failure: IOException) {
            loadFailure = failure
        }
    }

    /**
     * 작업을 앞선 작업 뒤에 줄 세운다. 호출한 순서가 곧 변경·조회 순서다 — 요청마다 코루틴을
     * 따로 띄우면 먼저 누른 변경의 다시 읽기가 나중 변경보다 늦게 끝나 낡은 목록이 남는다.
     *
     * [Job.join] 은 앞선 작업이 실패·취소로 끝나도 예외를 올리지 않는다 — 한 요청의 실패가
     * 뒤이은 요청을 막지 않아야 하고, 각 작업은 자기 실패를 자기가 처리한다.
     */
    private fun enqueue(work: suspend () -> Unit) {
        val previous = lastWork
        lastWork = scope.launch {
            previous.join()
            work()
        }
    }
}

/**
 * 컴포지션 수명에 묶인 계정 탭 상태. 첫 조합에서 한 번 읽어 둔다.
 *
 * 탭 Content 시그니처가 골격에 고정돼 있어 홀더를 밖에서 받을 수 없다 — 그래서 이 자리에서
 * 만들되, 변경이 취소로 끊기지 않는 책임은 [AccountPreferencesState] 안에 둔다.
 */
@Composable
fun rememberAccountPreferencesState(identity: IdentityUseCases): AccountPreferencesState {
    val scope = rememberCoroutineScope()
    val state = remember(scope, identity) { AccountPreferencesState(scope, identity) }
    LaunchedEffect(state) { state.refresh() }
    return state
}
