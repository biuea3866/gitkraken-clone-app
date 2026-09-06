package dev.undine.domain.patch

import dev.undine.domain.CommitId

/** 커밋 하나의 패치 바이트. 파일명은 호출자가 정한다 — Gateway 는 파일을 모른다. */
data class CommitPatch(
    val commit: CommitId,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CommitPatch && commit == other.commit && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * commit.hashCode() + bytes.contentHashCode()
}

/**
 * 내보내기 결과. **커밋별과 통합본을 한 번에** 담는다 — 두 용도(커밋 단위로 보내기 / 통째로 적용하기)가
 * 다르므로 하나로 합치지 않고, 호출자가 같은 계산을 두 번 시키지도 않는다.
 *
 * 워킹트리·인덱스 내보내기에는 커밋이 없으므로 [perCommit] 이 비어 있고 [combined] 만 채워진다.
 *
 * **Gateway 는 패치를 저장하지 않는다.** 파일로 쓰는 것은 호출자(화면) 책임이다.
 */
data class PatchExport(
    val perCommit: List<CommitPatch>,
    val combined: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PatchExport && perCommit == other.perCommit && combined.contentEquals(other.combined))

    override fun hashCode(): Int = 31 * perCommit.hashCode() + combined.contentHashCode()
}
