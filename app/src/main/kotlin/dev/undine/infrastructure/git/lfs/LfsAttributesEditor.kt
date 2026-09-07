package dev.undine.infrastructure.git.lfs

import dev.undine.domain.lfs.LfsTrackingRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * LFS 추적 규칙을 `.gitattributes` 원문을 보존하며 읽고 쓴다.
 *
 * **가변 필드를 두지 않는다.** 특히 "이 파일을 우리가 만들었는가" 를 기억하지 않는다 — 그 기억은
 * 저장소 전환·인스턴스 재생성·사용자의 외부 편집 앞에서 틀리고, 틀리면 남의 파일을 지운다.
 * 판정에 쓰는 것은 편집 시점의 파일 내용뿐이고, **어떤 경우에도 파일을 삭제하지 않는다.**
 *
 * 편집은 **저장소 경로 단위로 직렬화한다.** 읽고-고쳐-쓰는 구간이 겹치면 나중 쓰기가 먼저 쓴 규칙
 * 변경을 삼키고, 사용자는 자기가 넣은 규칙이 사라진 것을 나중에야 안다.
 *
 * 경로는 **기다리기 전에 확정한다.** 대기(suspend)를 사이에 두고 "지금 어느 저장소인가" 를 다시
 * 물으면 깨어났을 때 답이 바뀌어 있을 수 있고, 그 답으로 남의 `.gitattributes` 를 고치게 된다.
 */
internal class LfsAttributesEditor(private val workingDirectory: suspend () -> Path) {

    /** 저장소별 편집 락. 전역 단일 락이 아니라 경로마다 따로 잡는다. */
    private val editLocks = ConcurrentHashMap<Path, Mutex>()

    suspend fun rules(): List<LfsTrackingRule> = onFile(targetDirectory()) { directory ->
        when (val content = GitAttributesFile.readIn(directory)) {
            AttributesContent.Absent -> emptyList()
            is AttributesContent.Present -> content.bytes.toTrackingRules()
        }
    }

    suspend fun track(pattern: String): List<LfsTrackingRule> {
        val rule = GitAttributesFile.toByteString(validPattern(pattern))
        return edit(targetDirectory()) { content -> GitAttributesRules.with(content, rule) }
    }

    suspend fun untrack(pattern: String): List<LfsTrackingRule> {
        val rule = GitAttributesFile.toByteString(validPattern(pattern))
        return edit(targetDirectory()) { content -> GitAttributesRules.without(content, rule) }
    }

    /**
     * 편집 결과를 [directory] 의 파일에 반영하고 갱신된 규칙을 돌려준다.
     *
     * 세 갈래를 구분한다. **있던 파일**은 내용이 비어도 0바이트로 남긴다. **없던 파일**은 더할
     * 규칙이 있을 때만 만든다 — 뺄 것이 없는 부재에 빈 파일을 만들면 "없음" 이 값으로 바뀐다.
     * 내용이 그대로면 쓰지 않는다.
     */
    private suspend fun edit(directory: Path, apply: (String) -> String): List<LfsTrackingRule> =
        editLocks.computeIfAbsent(directory) { Mutex() }.withLock {
            onFile(directory) { target ->
                val content = GitAttributesFile.readIn(target)
                val existing = when (content) {
                    AttributesContent.Absent -> null
                    is AttributesContent.Present -> content.bytes
                }
                val edited = apply(existing.orEmpty())
                val next = when {
                    existing != null -> edited
                    edited.isEmpty() -> null
                    else -> edited
                }
                // 읽은 내용을 함께 넘긴다 — 그 사이 파일이 바뀌었으면 덮지 않고 실패해야 한다.
                if (next != null && next != existing) GitAttributesFile.writeIn(target, content, next)
                next.orEmpty().toTrackingRules()
            }
        }

    /** 지금 어느 저장소인지 **먼저** 묻는다. 이 값이 그 호출이 끝까지 다룰 유일한 경로다. */
    private suspend fun targetDirectory(): Path = workingDirectory().toAbsolutePath().normalize()

    private suspend fun <T> onFile(directory: Path, block: (Path) -> T): T =
        withContext(Dispatchers.IO) { block(directory) }
}

/**
 * 단일 `.gitattributes` 패턴인지 보고, 아니면 **거부한다.**
 *
 * 공백·탭·CR/LF 가 섞이면 행 구조가 바뀌어 같은 문자열로 뺐을 때 원상 복구되지 않는다.
 * 조용히 다듬지 않는 이유가 그것이다 — 거부는 사용자가 바로 알지만, 조용한 변형은 나중에
 * "왜 안 지워지지" 로 돌아온다.
 */
private fun validPattern(pattern: String): String {
    require(pattern.isNotEmpty()) { "추적 패턴이 비어 있습니다" }
    require(pattern.none { character -> character.isWhitespace() }) {
        "추적 패턴에 공백·탭·줄바꿈을 쓸 수 없습니다: $pattern"
    }
    return pattern
}

private fun String.toTrackingRules(): List<LfsTrackingRule> =
    GitAttributesRules.patternsIn(this)
        .map { pattern -> LfsTrackingRule(GitAttributesFile.fromByteString(pattern)) }
