package dev.undine.domain.gitconfig

/** Git 이 참으로 읽는 철자. `git config --bool` 과 같은 목록이다. */
private val TRUE_SPELLINGS = setOf("true", "yes", "on", "1")

/** Git 이 거짓으로 읽는 철자. */
private val FALSE_SPELLINGS = setOf("false", "no", "off", "0")

/**
 * 값이 실제로 어느 Git 설정 범위에서 왔는지.
 *
 * **앱 설정은 여기에 없다** (결정 G34 UND-75 1). Git 설정 게이트웨이가 앱 `Settings` 를 알면
 * 두 도메인이 섞이고, 앱 설정과의 결합은 소비자(UND-82)의 몫이다.
 *
 * 시스템 설정을 전역과 뭉뚱그리지 않는 이유는 **행동할 수 없는 답은 틀린 답**이기 때문이다
 * (결정 G35 UND-75 1) — `~/.gitconfig` 를 고쳐도 값이 안 바뀌는 이유를 사용자가 알아야 한다.
 *
 * **선언 순서가 곧 Git 의 우선순위다** (저장소 > 전역 > 시스템). 구현이 이 순서로 훑는다.
 */
enum class GitConfigSource {
    /** 그 저장소의 `.git/config`. */
    REPOSITORY,

    /** 사용자 전역 설정 (`~/.gitconfig`). */
    GLOBAL,

    /** 기계 전역 설정 (`/etc/gitconfig`). */
    SYSTEM,
}

/**
 * 어떤 Git 설정 키의 **실효값과 그 출처**.
 *
 * 값을 문자열 하나로 들고 해석은 domain 이 한다 (결정 G35 UND-75 3). 키마다 타입을 가른 sealed
 * 계층은 만들지 않는다 — 키 아홉 개에 과한 구조다.
 *
 * 이 타입은 **값이 있을 때만** 만들어진다. 세 범위 어디에도 없는 키는 `null`(부재)이고, 설정
 * 파일을 읽지 못한 것은 예외다 — 부재와 실패를 섞으면 손상된 파일이 "설정 안 함" 으로 보인다.
 */
data class EffectiveValue(
    val raw: String,
    val source: GitConfigSource,
) {

    /**
     * Git 철자를 Boolean 으로 해석한다 (`commit.gpgsign` 등).
     *
     * 화면이 `"yes"` 를 직접 비교하게 두면 Git 지식이 presentation 으로 샌다. 해석할 수 없는
     * 값은 `null` 이다 — **판단 불가는 실패가 아니라 판단 생략**이고, raw 값은 그대로 보여 줄 수 있다.
     */
    fun asBoolean(): Boolean? = when (raw.trim().lowercase()) {
        in TRUE_SPELLINGS -> true
        in FALSE_SPELLINGS -> false
        else -> null
    }
}
