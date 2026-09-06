package dev.undine.presentation.patch

import java.io.IOException
import java.nio.file.Path

/**
 * 여러 파일 저장의 결과.
 *
 * 실패는 **되돌리기 결과까지 함께** 담는다 — 복원이 실패한 사실을 로그로만 남기면, 사용자는 자기
 * 폴더가 어떤 상태인지 모른 채 "저장 실패" 만 보게 된다 (exception-handling 규칙 8과 같은 선).
 *
 * [Failed.leftoverTemporaries] 도 같은 이유로 함께 올린다 — 지우지 못한 임시 파일은 사용자가 고른
 * 폴더에 반쯤 쓰인 채 남고, 치울 수 있는 것은 사용자뿐이다.
 */
internal sealed interface PatchWriteResult {

    data object Written : PatchWriteResult

    data class Failed(
        val failure: IOException,
        val rollbackFailure: IOException?,
        val leftoverTemporaries: List<Path> = emptyList(),
    ) : PatchWriteResult
}

/**
 * 대상들을 **전부 쓰거나 아무것도 남기지 않는다.**
 *
 * 커밋당 파일 저장은 파일 여러 개를 이어서 쓴다. 도중에 하나가 실패하면 앞선 파일만 남아, 사용자는
 * 절반짜리 패치 묶음을 손에 쥔다 — 덮어쓴 경우에는 원래 갖고 있던 패치까지 사라진다. 그래서
 *
 * 1. 덮어쓸 파일은 **먼저 원래 내용을 읽어 두고**,
 * 2. 새로 만든 파일 경로를 기록하며,
 * 3. 실패하면 새 파일을 지우고 원래 내용을 되돌린다.
 *
 * 각 파일 쓰기 자체는 [PatchFiles.writeAtomically] 가 임시 파일을 거쳐 바꿔치기하므로, 한 파일이
 * 반쯤 쓰인 채 남는 경우는 없다. 되돌리기는 **끝까지 시도**하고, 그중 첫 실패를 결과에 실어 올린다.
 *
 * 임시 파일을 지우지 못한 사실은 실패에 `suppressed` 로 붙어 오므로 쓰기 실패와 되돌리기 실패
 * **양쪽에서** 모은다 — 되돌리기가 다시 쓰다 남긴 임시 파일도 사용자 폴더에 그대로 남는다.
 */
internal fun PatchFiles.writeAllOrRollback(entries: List<PatchFileWrite>): PatchWriteResult {
    val replaced = mutableListOf<PatchFileWrite>()
    val created = mutableListOf<PatchFileWrite>()
    try {
        entries.forEach { entry ->
            if (exists(entry.path)) {
                replaced += PatchFileWrite(entry.path, read(entry.path))
            } else {
                created += entry
            }
            writeAtomically(entry.path, entry.bytes)
        }
    } catch (failure: IOException) {
        val rollbackFailures = rollback(replaced, created)
        return PatchWriteResult.Failed(
            failure = failure,
            rollbackFailure = rollbackFailures.firstOrNull(),
            leftoverTemporaries = (listOf(failure) + rollbackFailures).flatMap { it.leftoverTemporaries() }.distinct(),
        )
    }
    return PatchWriteResult.Written
}

/** 되돌리기는 한 건이 실패해도 나머지를 계속한다 — 복원할 수 있는 만큼은 복원한다. */
private fun PatchFiles.rollback(replaced: List<PatchFileWrite>, created: List<PatchFileWrite>): List<IOException> {
    val failures = mutableListOf<IOException>()
    created.forEach { entry ->
        try {
            delete(entry.path)
        } catch (failure: IOException) {
            failures += failure
        }
    }
    replaced.forEach { entry ->
        try {
            writeAtomically(entry.path, entry.bytes)
        } catch (failure: IOException) {
            failures += failure
        }
    }
    return failures
}
