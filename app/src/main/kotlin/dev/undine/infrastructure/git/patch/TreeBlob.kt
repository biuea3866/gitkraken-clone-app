package dev.undine.infrastructure.git.patch

import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId

/**
 * 트리 항목 하나 — 파일 mode 와 blob.
 *
 * 복원이 이 두 값만으로 끝난다는 것이 트리 기반 트랜잭션의 요점이다. mode 는 실행 권한과
 * 심볼릭 링크 여부를 함께 담으므로, 복원할 때 "실행 비트를 되돌린다" 같은 항목을 따로 열거하지 않는다.
 */
internal data class TreeBlob(val fileMode: FileMode, val blobId: ObjectId)
