package dev.undine.infrastructure.git.lfs

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import java.nio.charset.StandardCharsets

/** 포인터 파일의 첫 줄. LFS 스펙이 고정한 값이다. */
private const val POINTER_VERSION_LINE = "version https://git-lfs.github.com/spec/v1"

/** `oid sha256:<64자리 소문자 16진수>`. */
private val POINTER_OID_LINE = Regex("""^oid sha256:[0-9a-f]{64}$""")

/** `size <바이트>`. 선행 0 을 허용하지 않는 십진수다. */
private val POINTER_SIZE_LINE = Regex("""^size (?:0|[1-9][0-9]*)$""")

/** 포인터 파일 크기 상한. 스펙상 몇백 바이트라, 넘는 blob 은 읽지 않고 아니라고 판정한다. */
private const val MAX_POINTER_BYTES = 1024L

/** version·oid·size 세 행과 마지막 줄바꿈 뒤 빈 조각. */
private const val MINIMUM_POINTER_PARTS = 4

/**
 * blob 이 LFS 포인터인지 본다.
 *
 * diff 판정 체인에 앞서 놓이므로 **비용이 먼저 싸야** 한다 — 크기를 blob 헤더로 확인해 상한을
 * 넘으면 내용을 읽지 않는다. 포인터로 판정되면 diff 는 포인터 텍스트 대신 "LFS 객체" 안내를
 * 보여 준다. 그러지 않으면 사용자가 포인터 원문을 보고 "파일이 깨졌다" 고 오해한다.
 */
internal fun ObjectReader.isLfsPointerBlob(blob: ObjectId): Boolean {
    if (getObjectSize(blob, Constants.OBJ_BLOB) > MAX_POINTER_BYTES) return false
    return isLfsPointerContent(String(open(blob, Constants.OBJ_BLOB).cachedBytes, StandardCharsets.UTF_8))
}

/**
 * 첫 줄의 version 선언, 형식에 맞는 `oid`, 형식에 맞는 `size` 가 **모두** 있을 때만 포인터다.
 *
 * 선언 한 줄로 판정하면 그 문구를 본문에 담은 평범한 텍스트 파일의 **진짜 diff 가 숨는다** —
 * 사용자는 변경을 못 보고 왜 안 보이는지도 모른다. 애매하면 숨기는 쪽이 아니라 보여 주는
 * 쪽으로 틀린다.
 */
internal fun isLfsPointerContent(content: String): Boolean {
    // 포인터는 항상 줄바꿈으로 끝난다 — 쪼갠 마지막 조각은 비어 있어야 한다.
    val lines = content.split('\n')
        .takeIf { parts -> parts.size >= MINIMUM_POINTER_PARTS && parts.last().isEmpty() }
        ?.dropLast(1)
        ?: return false
    return lines.first() == POINTER_VERSION_LINE &&
        lines.any { line -> POINTER_OID_LINE.matches(line) } &&
        lines.any { line -> POINTER_SIZE_LINE.matches(line) }
}
