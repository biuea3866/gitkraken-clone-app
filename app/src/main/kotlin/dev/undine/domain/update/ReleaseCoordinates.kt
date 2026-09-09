package dev.undine.domain.update

private const val OWNER_SEPARATOR = '/'
private const val COORDINATE_PARTS = 2

/**
 * 릴리즈를 조회할 GitHub 저장소.
 *
 * 실행 중인 앱은 자기가 어느 저장소에서 왔는지 모르므로 좌표가 빌드에 심긴다 — 심는 자리는
 * `BuildInfo` **한 곳**이다 (`packaging/RELEASE-CONTRACT.md` "좌표는 클라이언트에서 한 곳에만 둔다").
 * 문자열을 여러 파일에 흩으면 저장소를 옮길 때 하나가 남는다.
 */
data class ReleaseCoordinates(val owner: String, val repository: String) {

    init {
        require(owner.isNotBlank()) { "릴리즈 저장소 소유자가 비어 있습니다" }
        require(repository.isNotBlank()) { "릴리즈 저장소 이름이 비어 있습니다" }
    }

    companion object {

        /** `owner/repository` 를 읽는다. 형식이 아니면 `null` 이다 — 빌드 배선이 틀렸다는 뜻이다. */
        fun parse(raw: String): ReleaseCoordinates? {
            val parts = raw.trim().split(OWNER_SEPARATOR)
            return if (parts.size == COORDINATE_PARTS && parts.none(String::isBlank)) {
                ReleaseCoordinates(owner = parts[0], repository = parts[1])
            } else {
                null
            }
        }
    }
}
