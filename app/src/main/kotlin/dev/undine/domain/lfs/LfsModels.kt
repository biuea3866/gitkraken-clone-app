package dev.undine.domain.lfs

/** `git lfs version` 이 낸 첫 줄 원문. 최소 버전을 강제하지 않으므로 비교하지 않고 그대로 보여 준다. */
data class LfsVersion(val raw: String)

/**
 * `.gitattributes` 에 들어 있는 LFS 추적 규칙 한 건.
 *
 * 규칙 전문이 아니라 **패턴만** 보유한다 — 나머지 속성(`filter=lfs diff=lfs merge=lfs -text`)은
 * LFS 규칙의 고정 형태라 화면이 다시 보여 줄 값이 아니다.
 */
data class LfsTrackingRule(val pattern: String)

/**
 * 워킹트리에 있는 LFS 객체 하나의 상태.
 *
 * **포인터만 있는 것과 실제 내용이 내려받아진 것을 구분**하는 것이 이 타입의 존재 이유다.
 * 둘을 같게 다루면 사용자가 포인터 텍스트를 파일 내용으로 오해한다.
 */
data class LfsObject(
    val objectId: String,
    val path: String,
    val state: LfsObjectState,
)

enum class LfsObjectState {

    /** 포인터만 있고 실제 객체는 아직 없다 (`git lfs ls-files` 의 `-`). */
    POINTER_ONLY,

    /** 실제 객체가 워킹트리에 있다 (`git lfs ls-files` 의 `*`). */
    DOWNLOADED,
}

/**
 * 받을 객체 목록과 합계 바이트.
 *
 * **이 값을 만드는 조회는 아무것도 내려받지 않는다.** LFS 대역폭은 유료 한도가 있는 서비스가 많아,
 * 받기 전에 얼마나 받을지 먼저 말해야 한다. 실제 수신은 별도 호출이다
 * ([LfsGateway.pendingDownload] · [LfsGateway.download]).
 */
data class LfsDownloadEstimate(
    val objects: List<LfsObject>,
    val totalBytes: Long,
)

/** 서버가 잡고 있는 잠금 한 건. 생성·해제는 제공하지 않으므로 조회에 필요한 두 값만 갖는다. */
data class LfsLock(val path: String, val owner: String)

/**
 * 잠금 기능의 현재 상태.
 *
 * 잠금은 서버가 지원할 때만 쓸 수 있는 **부가 기능**이다. 지원하지 않는 서버에서 조회가 실패하는
 * 것은 사고가 아니라 정상 상태라, 조회 실패로 올려 화면 전체를 막지 않고 [Inactive] 로 보고한다.
 * 대신 사유를 [Inactive.detail] 에 그대로 실어 **왜 못 쓰는지가 사라지지 않게** 한다.
 */
sealed interface LfsLockState {

    data class Active(val locks: List<LfsLock>) : LfsLockState

    data class Inactive(val detail: String) : LfsLockState
}
