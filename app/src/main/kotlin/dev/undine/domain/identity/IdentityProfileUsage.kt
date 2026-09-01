package dev.undine.domain.identity

import dev.undine.domain.Person

/**
 * 프로필을 지운 뒤 저장소들이 따르게 될 전역 Git 신원.
 *
 * **"설정 안 함" 과 "읽지 못함" 을 절대 같은 값으로 접지 않는다** (결정 G36). 삭제 확인은 파괴적
 * 동작의 마지막 관문이라 여기서 틀린 말을 하면 사용자가 그 말을 믿고 지운다 — `~/.gitconfig` 를
 * 읽지 못한 것을 "적용될 identity 없음" 으로 말하면, 사실은 있는 신원을 없다고 알리는 것이다.
 */
sealed interface GlobalIdentity {

    /** 이름과 이메일이 둘 다 있는 전역 신원. 삭제 후 저장소들이 이 신원으로 커밋한다. */
    data class Configured(val person: Person) : GlobalIdentity

    /**
     * 전역 설정을 읽었고 신원이 없었다. 이름·이메일 중 **한쪽만** 있는 반쪽 설정도 여기다 —
     * 둘 다 있어야 git 이 커밋할 수 있으므로 반쪽은 설정하지 않은 것과 같은 결과를 낸다.
     */
    data object NotConfigured : GlobalIdentity

    /**
     * 전역 설정을 **열지 못했거나 파싱하지 못했다**. 신원이 있는지 없는지 알 수 없다는 뜻이지
     * 없다는 뜻이 아니다. 읽기 실패를 예외로 올리지 않는 이유는 전역 설정이 깨진 기계에서 삭제
     * 확인 자체가 막히면 안 되기 때문이다 — 모르는 것은 모른다고 말하고 진행한다.
     */
    data object Unreadable : GlobalIdentity
}

/**
 * 프로필을 지우기 전에 사용자가 알아야 하는 것 한 벌.
 *
 * 조용히 지우면 사용자는 다음 커밋에서 엉뚱한 이름으로 커밋한다. 그래서 **몇 개의 저장소가 영향을
 * 받는지**와 **그 저장소들이 그 뒤로 무엇을 쓰게 되는지**를 함께 답한다.
 *
 * @property repositoryCount 이 프로필을 쓰는 것으로 **확인된** 후보 저장소 수. 후보 집합은 사람이
 * 실제로 연 `Settings.recentRepositories` 이고, **집계 단위는 탭이 아니라 저장소**다. 아무도 쓰지
 * 않거나 후보 목록이 비었으면 `0` 이다 — 실패가 아니다.
 * @property uncheckedRepositoryCount 저장소는 있는데 **확인하지 못한** 후보 수. 집계가 전수인지
 * 아닌지를 화면이 알아야 한다 (결정 G36) — "2개가 씁니다" 와 "2개가 쓰고 3개는 확인 못 했습니다"
 * 는 다른 말이다. 경로가 사라졌거나 저장소가 아닌 후보는 확인할 저장소 자체가 없으므로 여기 들어가지
 * 않는다. 여기서도 집계 단위는 저장소라 별칭 경로는 한 번만 센다.
 * @property globalIdentity 삭제 후 그 저장소들에 적용될 전역 Git 신원 — 설정됨·설정 안 함·읽지
 * 못함 셋을 구분한다.
 */
data class IdentityProfileUsage(
    val repositoryCount: Int,
    val uncheckedRepositoryCount: Int,
    val globalIdentity: GlobalIdentity,
)
