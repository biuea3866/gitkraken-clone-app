package dev.undine.presentation.patch

/** 패치 파일 확장자. 단일 저장 대화상자의 기본 이름에도 이 값을 붙인다. */
const val PATCH_FILE_EXTENSION: String = ".patch"

/** 단일 통합 패치의 기본 파일 이름. 사용자가 대화상자에서 바꿀 수 있다. */
const val COMBINED_PATCH_FILE_NAME: String = "changes$PATCH_FILE_EXTENSION"

/**
 * 커밋별 파일명의 슬러그 최대 길이.
 *
 * `git format-patch` 의 제목 구간 관례(약 52자)에 맞춘다 — 파일 목록에서 어느 커밋인지 알아볼
 * 만큼은 남기되, 긴 제목이 파일명 길이 제한을 건드리지 않게 자른다.
 */
private const val MAX_SLUG_LENGTH = 50

private const val SEQUENCE_DIGITS = 4
private const val SLUG_SEPARATOR = "-"
private val NON_SLUG_CHARACTERS = Regex("[^a-z0-9]+")

/**
 * 커밋당 파일의 이름 — `0001-<요약-슬러그>.patch`.
 *
 * `git format-patch` 관례를 따른다: 4자리 순번(1부터) + 커밋 요약 슬러그. 순번은
 * `PatchExport.perCommit` 의 순서이므로, 목록 순서대로 저장하면 파일 이름 정렬이 적용 순서와 같다.
 *
 * 슬러그는 소문자 영숫자와 하이픈으로만 만든다. 요약이 비었거나 남는 글자가 없으면 **순번만** 쓴다 —
 * 이름을 지어내지 않는다.
 *
 * @param sequence 1부터 시작하는 순번.
 * @param summary 커밋 요약(첫 줄). 패치에 메타데이터가 없으면 `null` 이다.
 */
fun perCommitPatchFileName(sequence: Int, summary: String?): String {
    require(sequence >= 1) { "패치 순번은 1 이상이어야 합니다: $sequence" }
    val number = sequence.toString().padStart(SEQUENCE_DIGITS, '0')
    val slug = summary.orEmpty()
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .lowercase()
        .replace(NON_SLUG_CHARACTERS, SLUG_SEPARATOR)
        .trim('-')
        .take(MAX_SLUG_LENGTH)
        .trim('-')
    return if (slug.isEmpty()) "$number$PATCH_FILE_EXTENSION" else "$number-$slug$PATCH_FILE_EXTENSION"
}
