package dev.undine.domain.undo

import dev.undine.domain.RefName

/**
 * 되돌리기 요청의 결과.
 *
 * 거부는 **예외가 아니라 결과**다. `UndineException` 은 `domain/` 루트의 sealed 라 다른 패키지에서
 * 늘릴 수 없기도 하지만, 애초에 "되돌릴 수 없다"·"밖에서 바뀌었다" 는 사고가 아니라 예상되는
 * 정상 응답이다. 화면은 [Refused.reason] 을 그대로 보여줄 수 있다.
 */
sealed interface UndoOutcome {

    /** 되돌렸다. [strategy] 는 실제로 수행한 방법이다. */
    data class Undone(
        val operation: GitOperationKind,
        val strategy: UndoStrategy.Reversible,
    ) : UndoOutcome

    /** 되돌리지 않았다. 저장소는 그대로다. */
    sealed interface Refused : UndoOutcome {
        val reason: String
    }

    /** 되돌릴 연산이 없다. */
    data object NothingToUndo : Refused {
        override val reason: String = "되돌릴 연산이 없습니다."
    }

    /** 기록 시점에 이미 복구 불가로 남긴 연산이다. */
    data class Irreversible(val operation: GitOperationKind, val detail: String) : Refused {
        override val reason: String = "${operation.label} 은(는) 되돌릴 수 없습니다: $detail"
    }

    /** 기록 이후 앱 밖에서 저장소가 바뀌었다. 그대로 되돌리면 엉뚱한 커밋으로 간다. */
    data class ExternalChange(
        val recorded: RepositoryBaseline,
        val current: RepositoryBaseline,
    ) : Refused {
        override val reason: String =
            "기록한 뒤 저장소가 앱 밖에서 바뀌어 되돌리지 않았습니다 " +
                "(기록: ${recorded.describe()}, 지금: ${current.describe()})."
    }

    /** 브랜치 위가 아니다 (detached HEAD 또는 커밋이 없는 저장소). */
    data class NoCurrentBranch(val operation: GitOperationKind) : Refused {
        override val reason: String =
            "브랜치를 체크아웃한 상태에서만 되돌릴 수 있습니다 — 지금은 detached HEAD 입니다 " +
                "(${operation.label})."
    }

    /** 커밋되지 않은 변경이 있어 워킹트리를 덮어쓰는 되돌리기를 하지 않았다. */
    data class UncommittedChanges(val paths: List<String>) : Refused {
        override val reason: String =
            "커밋되지 않은 변경이 ${paths.size}개 있어 되돌리지 않았습니다 — 먼저 커밋하거나 stash 하세요."
    }

    /** 되돌리며 지우려는 브랜치에 병합되지 않은 커밋이 있다. 강제 삭제로 승격하지 않는다. */
    data class UnmergedBranch(val branch: RefName) : Refused {
        override val reason: String =
            "브랜치 '${branch.value}' 에 병합되지 않은 커밋이 있어 삭제하지 않았습니다."
    }
}

private fun RepositoryBaseline.describe(): String {
    val branchLabel = branch?.value ?: "detached HEAD"
    val headLabel = head?.value?.take(SHORT_HASH_LENGTH) ?: "커밋 없음"
    return "$branchLabel@$headLabel"
}

/** 사람이 읽는 짧은 해시 길이 — git 관례를 따른다. */
private const val SHORT_HASH_LENGTH = 7
