package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `submoduleworktree.*` 네임스페이스 — 서브모듈 상태 표시와 worktree 관리 패널의 문구.
 *
 * 두 패널을 한 네임스페이스로 두는 것은 소유 티켓이 하나이기 때문이다 — 나뉘어야 할 만큼 커지면
 * 그때 UND-45 가 자기 파일을 쪼갠다.
 *
 * **아직 비어 있다.** UND-63 이 [builtInTranslations] 등록까지만 해 두고, 키 정의 object·접근자
 * value class·로케일별 번역은 UND-45(Submodule · Worktree 패널)가 **이 파일 안에서만** 채운다.
 *
 * 채우는 모양은 [CommonStrings] 가 정본이다: [SUBMODULE_WORKTREE_NAMESPACE] 로 키를 만들고,
 * 번역 맵을 로케일별로 채우고, `Strings.submoduleWorktree` 확장 프로퍼티로 노출한다.
 */
internal const val SUBMODULE_WORKTREE_NAMESPACE: String = "submoduleworktree"

/** 서브모듈·worktree 화면이 추가할 `submoduleworktree.*` 키의 자리. */
object SubmoduleWorktreeKeys

/** 서브모듈·worktree 문구 접근자. UND-45 가 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class SubmoduleWorktreeStrings internal constructor(private val strings: Strings)

/** 서브모듈·worktree 문구 네임스페이스 진입점. */
val Strings.submoduleWorktree: SubmoduleWorktreeStrings get() = SubmoduleWorktreeStrings(this)

internal val submoduleWorktreeTranslations: Map<Locale, Map<StringKey, String>> = emptyMap()
