package dev.undine.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class DomainEnumSpec : FunSpec({

    test("RepositoryState 는 정상·빈저장소·병합중·리베이스중·detached 다섯 값으로 닫혀 있다") {
        RepositoryState.entries.map { it.name } shouldContainExactly
            listOf("NORMAL", "EMPTY", "MERGING", "REBASING", "DETACHED")
    }

    test("InvalidRepositoryPath.Reason 은 네 사유로 닫혀 있다") {
        UndineException.InvalidRepositoryPath.Reason.entries.map { it.name } shouldContainExactly
            listOf("NOT_FOUND", "NOT_A_REPOSITORY", "PERMISSION_DENIED", "BARE_REPOSITORY")
    }

    test("RepositoryState 는 정의되지 않은 이름으로 만들 수 없다") {
        shouldThrow<IllegalArgumentException> { RepositoryState.valueOf("CHERRY_PICKING") }
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
