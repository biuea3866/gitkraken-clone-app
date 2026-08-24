package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "toolbar"

/**
 * `toolbar.*` 키 정의 — 원격 작업 버튼·진행·결과 안내 문구.
 *
 * 결과 문구는 **계약이 실제로 주는 정보**까지만 말한다. fetch 는 돌려받은 참조 수를 쓰고,
 * 단일 참조를 올리는 push 는 갱신 건수를 주장하지 않는다.
 *
 * `MessageFormat` 패턴이라 작은따옴표를 쓰지 않는다 — 인용부호가 인자 치환을 통째로 막는다.
 */
object ToolbarKeys {
    val fetch = StringKey("$NAMESPACE.fetch")
    val pull = StringKey("$NAMESPACE.pull")
    val push = StringKey("$NAMESPACE.push")
    val moreActions = StringKey("$NAMESPACE.moreActions")
    val forcePush = StringKey("$NAMESPACE.forcePush")
    val forcePushWarning = StringKey("$NAMESPACE.forcePushWarning")
    val forcePushConfirm = StringKey("$NAMESPACE.forcePushConfirm")
    val noRemote = StringKey("$NAMESPACE.noRemote")
    val detachedHead = StringKey("$NAMESPACE.detachedHead")
    val noUpstream = StringKey("$NAMESPACE.noUpstream")
    val aheadBehind = StringKey("$NAMESPACE.aheadBehind")
    val fetched = StringKey("$NAMESPACE.result.fetched")
    val pulled = StringKey("$NAMESPACE.result.pulled")
    val pushed = StringKey("$NAMESPACE.result.pushed")
    val forcePushed = StringKey("$NAMESPACE.result.forcePushed")
    val nonFastForward = StringKey("$NAMESPACE.result.nonFastForward")
    val remoteRejected = StringKey("$NAMESPACE.result.remoteRejected")
    val authenticationFailed = StringKey("$NAMESPACE.result.authenticationFailed")
    val remoteNotFound = StringKey("$NAMESPACE.result.remoteNotFound")
    val conflict = StringKey("$NAMESPACE.result.conflict")
    val dirtyWorkingTree = StringKey("$NAMESPACE.result.dirtyWorkingTree")
    val unexpectedFailure = StringKey("$NAMESPACE.result.unexpectedFailure")
    val cancelling = StringKey("$NAMESPACE.cancelling")
    val cancelledFetch = StringKey("$NAMESPACE.result.cancelledFetch")
    val cancelledPull = StringKey("$NAMESPACE.result.cancelledPull")
    val cancelledPush = StringKey("$NAMESPACE.result.cancelledPush")
    val cancelledForcePush = StringKey("$NAMESPACE.result.cancelledForcePush")

    /** 번역 누락 검사가 키를 하나씩 나열하지 않도록 전체 목록을 노출한다. */
    val all: List<StringKey> = listOf(
        fetch, pull, push, moreActions, forcePush, forcePushWarning, forcePushConfirm,
        noRemote, detachedHead, noUpstream, aheadBehind, fetched, pulled, pushed, forcePushed,
        nonFastForward, remoteRejected, authenticationFailed, remoteNotFound,
        conflict, dirtyWorkingTree, unexpectedFailure,
        cancelling, cancelledFetch, cancelledPull, cancelledPush, cancelledForcePush,
    )
}

/**
 * 툴바 문구 접근자. `strings.toolbar.fetch` 로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 공용 파일이라 등록을 UND-26 이 일괄로 한다
 * (wave 3 결정 A3).
 */
@JvmInline
value class ToolbarStrings internal constructor(private val strings: Strings) {
    val fetch: String get() = strings.text(ToolbarKeys.fetch)
    val pull: String get() = strings.text(ToolbarKeys.pull)
    val push: String get() = strings.text(ToolbarKeys.push)
    val moreActions: String get() = strings.text(ToolbarKeys.moreActions)
    val forcePush: String get() = strings.text(ToolbarKeys.forcePush)
    val forcePushConfirm: String get() = strings.text(ToolbarKeys.forcePushConfirm)
    val noRemote: String get() = strings.text(ToolbarKeys.noRemote)
    val detachedHead: String get() = strings.text(ToolbarKeys.detachedHead)

    val noUpstream: String get() = strings.text(ToolbarKeys.noUpstream)
    val pulled: String get() = strings.text(ToolbarKeys.pulled)
    val pushed: String get() = strings.text(ToolbarKeys.pushed)
    val forcePushed: String get() = strings.text(ToolbarKeys.forcePushed)
    val nonFastForward: String get() = strings.text(ToolbarKeys.nonFastForward)
    val remoteRejected: String get() = strings.text(ToolbarKeys.remoteRejected)
    val authenticationFailed: String get() = strings.text(ToolbarKeys.authenticationFailed)
    val remoteNotFound: String get() = strings.text(ToolbarKeys.remoteNotFound)
    val conflict: String get() = strings.text(ToolbarKeys.conflict)
    val dirtyWorkingTree: String get() = strings.text(ToolbarKeys.dirtyWorkingTree)
    val unexpectedFailure: String get() = strings.text(ToolbarKeys.unexpectedFailure)
    val cancelling: String get() = strings.text(ToolbarKeys.cancelling)
    val cancelledFetch: String get() = strings.text(ToolbarKeys.cancelledFetch)
    val cancelledPull: String get() = strings.text(ToolbarKeys.cancelledPull)
    val cancelledPush: String get() = strings.text(ToolbarKeys.cancelledPush)
    val cancelledForcePush: String get() = strings.text(ToolbarKeys.cancelledForcePush)

    /** 덮어쓸 대상을 문장으로 알린다 — 확인 버튼 옆의 한 줄이 아니라 무엇이 사라지는지까지 말한다. */
    fun forcePushWarning(branch: String, remote: String): String =
        strings.text(ToolbarKeys.forcePushWarning, branch, remote)

    fun aheadBehind(ahead: Int, behind: Int): String =
        strings.text(ToolbarKeys.aheadBehind, ahead, behind)

    fun fetched(refCount: Int): String = strings.text(ToolbarKeys.fetched, refCount)
}

/** 툴바 문구 네임스페이스 진입점. */
val Strings.toolbar: ToolbarStrings get() = ToolbarStrings(this)

internal val toolbarTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        ToolbarKeys.fetch to "가져오기",
        ToolbarKeys.pull to "가져와 병합",
        ToolbarKeys.push to "올리기",
        ToolbarKeys.moreActions to "원격 작업 더 보기",
        ToolbarKeys.forcePush to "강제로 올리기",
        ToolbarKeys.forcePushWarning to
            "{0} 브랜치의 원격 이력을 지금 로컬 이력으로 덮어씁니다. " +
            "원격 {1} 에만 있는 커밋은 이 앱에서 되돌릴 수 없습니다.",
        ToolbarKeys.forcePushConfirm to "덮어쓰기",
        ToolbarKeys.noRemote to "등록된 원격이 없어 원격 작업을 할 수 없습니다.",
        ToolbarKeys.detachedHead to "브랜치가 아닌 커밋에 있어 올리기를 할 수 없습니다.",
        ToolbarKeys.noUpstream to "현재 브랜치에 업스트림이 없어 올릴 원격을 정할 수 없습니다.",
        ToolbarKeys.aheadBehind to "올릴 커밋 {0}개 · 받을 커밋 {1}개",
        ToolbarKeys.fetched to "원격 참조 {0}개를 가져왔습니다.",
        ToolbarKeys.pulled to "원격 변경을 가져와 병합했습니다.",
        ToolbarKeys.pushed to "현재 브랜치를 원격에 올렸습니다.",
        ToolbarKeys.forcePushed to "현재 브랜치로 원격 이력을 덮어썼습니다.",
        ToolbarKeys.nonFastForward to "원격에 아직 받지 않은 커밋이 있습니다. pull 로 받은 뒤 다시 시도하세요.",
        ToolbarKeys.remoteRejected to "원격이 이 요청을 거절했습니다. 원격의 쓰기 권한과 보호 규칙을 확인하세요.",
        ToolbarKeys.authenticationFailed to "원격 인증에 실패했습니다. git 자격증명 설정을 확인한 뒤 다시 시도하세요.",
        ToolbarKeys.remoteNotFound to "원격을 찾을 수 없습니다. 저장소의 원격 설정을 확인하세요.",
        ToolbarKeys.conflict to "충돌이 생겨 병합을 마치지 못했습니다. 충돌을 해결한 뒤 이어서 진행하세요.",
        ToolbarKeys.dirtyWorkingTree to "커밋되지 않은 변경이 있어 진행할 수 없습니다. 커밋하거나 스태시한 뒤 다시 시도하세요.",
        ToolbarKeys.unexpectedFailure to "원격 작업이 실패했습니다. 다시 시도하고, 계속 실패하면 로그를 확인하세요.",
        ToolbarKeys.cancelling to "취소 중…",
        ToolbarKeys.cancelledFetch to "가져오기를 취소했습니다.",
        ToolbarKeys.cancelledPull to
            "가져와 병합을 취소했습니다. 병합이 이미 시작됐을 수 있으니 저장소 상태를 확인하세요.",
        ToolbarKeys.cancelledPush to
            "올리기를 취소했습니다. 취소 전에 전송이 끝났을 수 있으니 원격 브랜치 상태를 확인한 뒤 다시 시도하세요.",
        ToolbarKeys.cancelledForcePush to
            "강제로 올리기를 취소했습니다. 원격 이력이 이미 덮어써졌을 수 있습니다. " +
            "원격 상태를 확인하고, 되돌려야 하면 refs/undine/force-push-backup 의 백업 참조를 쓰세요.",
    ),
    Locale.ENGLISH to mapOf(
        ToolbarKeys.fetch to "Fetch",
        ToolbarKeys.pull to "Pull",
        ToolbarKeys.push to "Push",
        ToolbarKeys.moreActions to "More remote actions",
        ToolbarKeys.forcePush to "Force push",
        ToolbarKeys.forcePushWarning to
            "This overwrites the remote history of {0} with your local history. " +
            "Commits that exist only on {1} cannot be restored from this app.",
        ToolbarKeys.forcePushConfirm to "Overwrite",
        ToolbarKeys.noRemote to "This repository has no remote, so remote actions are unavailable.",
        ToolbarKeys.detachedHead to "You are on a detached commit, so pushing is unavailable.",
        ToolbarKeys.noUpstream to "This branch has no upstream, so there is no remote to push to.",
        ToolbarKeys.aheadBehind to "{0} to push · {1} to pull",
        ToolbarKeys.fetched to "Fetched {0} remote refs.",
        ToolbarKeys.pulled to "Pulled and merged remote changes.",
        ToolbarKeys.pushed to "Pushed the current branch to the remote.",
        ToolbarKeys.forcePushed to "Overwrote the remote history with the current branch.",
        ToolbarKeys.nonFastForward to "The remote has commits you do not have yet. pull them, then try again.",
        ToolbarKeys.remoteRejected to "The remote rejected this request. Check write access and branch protection.",
        ToolbarKeys.authenticationFailed to
            "Remote authentication failed. Check your git credential setup, then try again.",
        ToolbarKeys.remoteNotFound to "The remote was not found. Check the remote configuration.",
        ToolbarKeys.conflict to "The merge stopped on a conflict. Resolve the conflict, then continue.",
        ToolbarKeys.dirtyWorkingTree to "You have uncommitted changes. Commit or stash them, then try again.",
        ToolbarKeys.unexpectedFailure to "The remote action failed. Try again, and check the log if it keeps failing.",
        ToolbarKeys.cancelling to "Cancelling…",
        ToolbarKeys.cancelledFetch to "Cancelled the fetch.",
        ToolbarKeys.cancelledPull to
            "Cancelled the pull. The merge may have already started, so check the repository state.",
        ToolbarKeys.cancelledPush to
            "Cancelled the push. The transfer may have finished first, so check the remote branch before retrying.",
        ToolbarKeys.cancelledForcePush to
            "Cancelled the force push. The remote history may already be overwritten. " +
            "Check the remote, and restore from the backup ref under refs/undine/force-push-backup if needed.",
    ),
)
