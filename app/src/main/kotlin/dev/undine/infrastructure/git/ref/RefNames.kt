package dev.undine.infrastructure.git.ref

import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import org.eclipse.jgit.lib.Repository

internal const val LOCAL_BRANCH_PREFIX = "refs/heads/"
internal const val REMOTE_BRANCH_PREFIX = "refs/remotes/"
internal const val TAG_PREFIX = "refs/tags/"

private const val ANY_REF_PREFIX = "refs/"

/**
 * ref 이름 형식 검증은 규칙을 직접 옮기지 않고 [Repository.isValidRefName] 에 위임한다 —
 * `..` 금지·`.lock` 접미사 금지·제어문자 금지 규칙을 손으로 옮기면 정상 ref 를 거부하게 된다.
 *
 * [RefName] 은 짧은 이름(`main`)과 전체 이름(`refs/heads/main`) 을 모두 받는다.
 */
internal fun validatedBranchRef(name: RefName): String = validatedFullRef(name, LOCAL_BRANCH_PREFIX)

internal fun validatedTagRef(name: RefName): String = validatedFullRef(name, TAG_PREFIX)

/**
 * 체크아웃 대상은 로컬 브랜치·원격 추적 브랜치·태그 어느 것이든 될 수 있으므로
 * 짧은 이름 하나를 후보 전체 이름 목록으로 펼친다. 순서가 우선순위다 —
 * 원격 추적 브랜치를 태그보다 먼저 보는 것은 이 앱이 원격 브랜치 체크아웃을 명시적으로 지원하기 때문이다.
 */
internal fun validatedCheckoutCandidates(name: RefName): List<String> {
    if (name.value.startsWith(ANY_REF_PREFIX)) return listOf(validatedFullRef(name, ANY_REF_PREFIX))
    validatedFullRef(name, LOCAL_BRANCH_PREFIX)
    return listOf(
        LOCAL_BRANCH_PREFIX + name.value,
        REMOTE_BRANCH_PREFIX + name.value,
        TAG_PREFIX + name.value,
    )
}

/**
 * `refs/remotes/origin/feature` → `feature`. 원격 이름 한 구간만 떼어내므로
 * `refs/remotes/origin/feature/login` 은 `feature/login` 이 된다.
 */
internal fun localTrackingNameOf(remoteFullRef: String): String =
    remoteFullRef.removePrefix(REMOTE_BRANCH_PREFIX).substringAfter('/')

private fun validatedFullRef(name: RefName, prefix: String): String {
    val fullRef = if (name.value.startsWith(ANY_REF_PREFIX)) name.value else prefix + name.value
    if (!Repository.isValidRefName(fullRef)) throw UndineException.InvalidRefName(name.value)
    return fullRef
}
