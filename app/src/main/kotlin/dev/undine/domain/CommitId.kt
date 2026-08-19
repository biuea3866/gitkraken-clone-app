package dev.undine.domain

/**
 * 커밋 해시. 40자 hexadecimal 만 허용하고 **소문자로 정규화**해 보관한다 —
 * 여러 출처에서 붙여넣은 해시의 대소문자가 섞여도 동치성이 깨지지 않아야 한다.
 */
@JvmInline
value class CommitId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private const val HEX_LENGTH = 40
        private val HEX_PATTERN = Regex("^[0-9a-fA-F]{$HEX_LENGTH}$")

        fun of(raw: String): CommitId {
            if (!HEX_PATTERN.matches(raw)) throw UndineException.InvalidCommitId(raw)
            return CommitId(raw.lowercase())
        }
    }
}
