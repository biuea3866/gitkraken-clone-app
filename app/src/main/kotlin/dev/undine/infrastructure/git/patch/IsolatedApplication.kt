package dev.undine.infrastructure.git.patch

import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.UnsupportedReason
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.patch.FormatError
import org.eclipse.jgit.patch.Patch
import org.eclipse.jgit.patch.PatchApplier
import org.eclipse.jgit.revwalk.RevWalk

/** 격리 적용의 결과. 통과하면 새 트리를, 아니면 승격하지 않을 이유를 담는다. */
internal sealed interface IsolatedApplication {

    data class Applied(val treeId: ObjectId, val paths: List<String>) : IsolatedApplication

    data class Rejected(val outcome: ApplyOutcome) : IsolatedApplication
}

/** 패치 바이트를 파일 단위로 읽는다. 형식 자체가 깨진 패치는 적용 이전에 거부한다. */
internal fun parsePatchFiles(bytes: ByteArray): List<FileHeader> {
    val patch = Patch()
    patch.parse(bytes, 0, bytes.size)
    val fatal = patch.errors.filter { error -> error.severity == FormatError.Severity.ERROR }
    if (fatal.isNotEmpty()) {
        throw UndineException.StateViolation("패치를 읽을 수 없습니다: ${fatal.joinToString { it.message }}")
    }
    return patch.files
}

/** 패치가 건드리는 모든 경로 — 양쪽 이름을 다 본다. rename 의 원본 경로도 복원 대상이다. */
internal fun List<FileHeader>.affectedPaths(): List<String> =
    flatMap { header -> listOf(header.oldPath, header.newPath) }
        .filter { path -> path != DiffEntry.DEV_NULL }
        .distinct()

/**
 * 워킹트리를 건드리지 않고 **트리 위에서만** 패치를 적용해 본다.
 *
 * `dryRun` 과 `apply` 가 이 함수 하나를 공유한다 — 검사 전용 경로를 따로 두면 두 경로가 갈라져
 * "검사는 통과했는데 적용은 실패" 가 생긴다. 둘의 차이는 오직 결과 트리를 승격하느냐뿐이다.
 *
 * 한 파일이라도 붙지 않으면 **전부 승격하지 않는다** — 절반만 적용된 상태로 실패하면 사용자가
 * 수습할 방법이 없다. 결과 트리는 붙었을 때만 의미가 있다.
 *
 * `allowConflicts()` 를 쓰지 않는다. 그 경로는 충돌 표식이 박힌 트리를 만드는데, 이 계약은
 * 워킹트리에 표식을 남기지 않기로 했다.
 */
internal fun Repository.applyIsolated(
    baseTree: ObjectId,
    patch: ByteArray,
    files: List<FileHeader>,
    inserter: ObjectInserter,
): IsolatedApplication {
    val applied = applyToTree(baseTree, patch, 0, patch.size, inserter)
    if (applied != null) {
        inserter.flush()
        return IsolatedApplication.Applied(applied.treeId, applied.paths.distinct())
    }
    return IsolatedApplication.Rejected(classifyFailure(baseTree, files, inserter))
}

/**
 * **패치 전체를 한 번에** 적용한다. 파일마다 나눠 적용하면 안 되는 이유: git 은 패치 항목을 경로
 * 순으로 싣기 때문에 `a` 추가가 `a/b` 삭제보다 앞에 온다. 나눠 적용하면 `a` 를 넣는 순간 아직
 * 남아 있는 `a/b` 와 부딪혀, 실제로는 멀쩡한 패치가 충돌로 보고된다.
 *
 * 오류가 있을 때의 `treeId` 는 의미가 없으므로 `null` 로 접어 절대 쓰지 않는다.
 *
 * **붙지 않은 패치와 읽지 못한 저장소를 섞지 않는다.** 패치가 붙지 않았다는 사실은 JGit 이
 * `Result.errors` 로 알려 주고, 그것만 `null` 로 접는다. 반면 `applyPatch` 가 던지는 것은
 * `cannotReadFile`·`cannotDeleteFile` 같은 **저장소 I/O 실패**이지 패치 불일치가 아니다.
 * 그것을 여기서 삼키면 손상된 저장소가 "충돌" 로 보고돼 사용자가 정상 상태로 오인한다 —
 * 그대로 올려 `patchOperation` 이 `GitOperationFailed` 로 번역하게 둔다.
 */
private fun Repository.applyToTree(
    baseTree: ObjectId,
    buffer: ByteArray,
    start: Int,
    end: Int,
    inserter: ObjectInserter,
): PatchApplier.Result? {
    val parsed = Patch().apply { parse(buffer, start, end) }
    val result = RevWalk(this).use { walk ->
        PatchApplier(this, walk.parseTree(baseTree), inserter).applyPatch(parsed)
    }
    return result.takeIf { outcome -> outcome.errors.isEmpty() }
}

/**
 * 어느 파일이 붙지 않았는지 알아내려고 **실패했을 때만** 파일 단위로 다시 적용해 본다.
 *
 * `PatchApplier.Result.Error` 는 경로를 공개하지 않으므로 이 되짚기가 유일한 귀속 수단이다.
 * 성공 경로에는 영향이 없고, 이미 실패한 패치에만 드는 비용이다.
 *
 * 파일 단위로는 전부 붙는데 전체로는 실패했다면 원인이 특정 파일에 있지 않다는 뜻이므로,
 * 패치가 건드리는 경로 전체를 대상으로 보고한다 — 빈 목록을 돌려주면 화면이 "충돌 없음" 으로 읽는다.
 */
private fun Repository.classifyFailure(
    baseTree: ObjectId,
    files: List<FileHeader>,
    inserter: ObjectInserter,
): ApplyOutcome {
    val failed = files.filter { header ->
        applyToTree(baseTree, header.buffer, header.startOffset, header.endOffset, inserter) == null
    }
    return failureOutcome(failed.ifEmpty { files })
}

/**
 * 붙지 않은 이유를 **충돌**과 **미지원**으로 가른다.
 *
 * 기준은 "조상 blob 이 저장소에 있는가" 다. 있으면 git 은 `--3way` 로 3-way 병합을 시도하겠지만
 * JGit 표준 API 에는 조상 blob 기반 3-way 적용 경로가 없다 — 우리가 시도할 수단이 없는 것이지
 * 붙지 않는 패치가 아니므로 **미지원**으로 보고한다. 조상 blob 이 없으면 누구도 3-way 를 할 수
 * 없으므로 진짜 **충돌**이다.
 *
 * 섞여 있으면 미지원이 이긴다(`any`). 충돌로 보고하면 사용자는 표식을 풀면 된다고 읽는데,
 * 실제로는 우리가 3-way 를 시도조차 하지 않은 것이라 안내가 거짓이 된다.
 */
private fun Repository.failureOutcome(failed: List<FileHeader>): ApplyOutcome {
    val needsThreeWay = newObjectReader().use { reader ->
        failed.any { header -> header.hasAncestorBlob(reader) }
    }
    if (needsThreeWay) return ApplyOutcome.Unsupported(UnsupportedReason.THREE_WAY_REQUIRED)
    return ApplyOutcome.Conflicted(failed.map(FileHeader::targetPath).distinct())
}

/**
 * 조상 blob 이 저장소에 있는지 본다. **없는 것과 못 읽은 것을 같게 다루지 않는다** —
 * 객체가 없으면 `resolve` 가 빈 결과를 주고, 저장소를 읽지 못하면 예외를 던진다.
 * 후자를 "조상 없음" 으로 접으면 손상된 저장소가 충돌로 보고되므로 그대로 올린다.
 */
private fun FileHeader.hasAncestorBlob(reader: ObjectReader): Boolean {
    val abbreviated = oldId
    if (abbreviated == null || abbreviated.toObjectId() == ObjectId.zeroId()) return false
    val candidates = reader.resolve(abbreviated)
    return candidates.size == 1 && reader.has(candidates.first())
}

/** 삭제 패치는 새 이름이 `/dev/null` 이므로 사용자에게 보일 이름은 옛 경로다. */
internal fun FileHeader.targetPath(): String =
    newPath.takeIf { path -> path != DiffEntry.DEV_NULL } ?: oldPath
