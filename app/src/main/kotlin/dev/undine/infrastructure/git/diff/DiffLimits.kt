package dev.undine.infrastructure.git.diff

/**
 * hunk 를 계산할 최대 blob 크기 — 1 MiB (wave 2 결정).
 *
 * 이 값을 넘는 텍스트 파일은 내용을 읽지 않고 `DiffResult.NotComputed(TOO_LARGE)` 로 사유를 밝힌다.
 * domain 계약을 건드리지 않기 위해 infrastructure 패키지 안에 둔다.
 */
internal const val MAX_DIFF_BLOB_BYTES: Long = 1024L * 1024L
