package dev.undine.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class DomainEnumSpec : FunSpec({

    test("RepositoryState 는 정상·빈저장소·병합·리베이스·revert·cherry-pick·detached 일곱 값으로 닫혀 있다") {
        // cherry-pick 은 UND-28 이 그 상태를 필요로 해 추가했다 (enum KDoc 의 계약).
        RepositoryState.entries.map { it.name } shouldContainExactly
            listOf("NORMAL", "EMPTY", "MERGING", "REBASING", "REVERTING", "CHERRY_PICKING", "DETACHED")
    }

    test("NotFound.Kind 는 참조·커밋·스태시·원격 네 종류로 닫혀 있다") {
        UndineException.NotFound.Kind.entries.map { it.name } shouldContainExactly
            listOf("REF", "COMMIT", "STASH", "REMOTE")
    }

    test("InvalidRepositoryPath.Reason 은 네 사유로 닫혀 있다") {
        UndineException.InvalidRepositoryPath.Reason.entries.map { it.name } shouldContainExactly
            listOf("NOT_FOUND", "NOT_A_REPOSITORY", "PERMISSION_DENIED", "BARE_REPOSITORY")
    }

    test("RepositoryState 는 정의되지 않은 이름으로 만들 수 없다") {
        // bisect 는 아직 어느 티켓도 필요로 하지 않아 값이 없다 — 그 티켓이 추가할 때 이 이름을 바꾼다.
        shouldThrow<IllegalArgumentException> { RepositoryState.valueOf("BISECTING") }
    }

    test("ResetMode 는 SOFT·MIXED 만 갖는다 — hard 는 플래그가 아니라 별도 메서드다") {
        ResetMode.entries.map { it.name } shouldContainExactly listOf("SOFT", "MIXED")
    }

    test("ChangeType 은 다섯 가지 변경 종류로 닫혀 있다") {
        ChangeType.entries.map { it.name } shouldContainExactly
            listOf("ADDED", "MODIFIED", "DELETED", "RENAMED", "COPIED")
    }

    test("DiffLineType 은 문맥·추가·삭제 세 값으로 닫혀 있다") {
        DiffLineType.entries.map { it.name } shouldContainExactly
            listOf("CONTEXT", "ADDED", "DELETED")
    }
})
