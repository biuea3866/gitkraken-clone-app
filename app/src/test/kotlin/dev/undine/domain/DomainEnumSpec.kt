package dev.undine.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class DomainEnumSpec : FunSpec({

    test("RepositoryState 는 정상·빈저장소·병합·리베이스·revert·cherry-pick·detached 일곱 값으로 닫혀 있다") {
        // cherry-pick 은 UND-28 이 그 상태를 필요로 해 추가했다 (enum KDoc 의 계약).
        RepositoryState.entries.map { it.name } shouldContainExactly
            listOf("NORMAL", "EMPTY", "MERGING", "REBASING", "REVERTING", "CHERRY_PICKING", "DETACHED")
    }

    test("NotFound.Kind 는 참조·커밋·스태시·원격·경로·서브모듈·워크트리 일곱 종류로 닫혀 있다") {
        // 경로는 UND-29(blame)가 추가했다 — 그 커밋에 그 파일이 없는 경우를 참조 부재로 뭉개지 않는다.
        // 서브모듈(UND-32)·워크트리(UND-34)는 UND-59 가 공통 계약으로 미리 넓혔다.
        UndineException.NotFound.Kind.entries.map { it.name } shouldContainExactly
            listOf("REF", "COMMIT", "STASH", "REMOTE", "PATH", "SUBMODULE", "WORKTREE")
    }

    test("기존 다섯 Kind 의 라벨은 그대로다 — 새 값 추가가 기존 메시지를 바꾸지 않는다") {
        listOf(
            UndineException.NotFound.Kind.REF to "참조",
            UndineException.NotFound.Kind.COMMIT to "커밋",
            UndineException.NotFound.Kind.STASH to "스태시",
            UndineException.NotFound.Kind.REMOTE to "원격",
            UndineException.NotFound.Kind.PATH to "경로",
        ).forEach { (kind, label) ->
            kind.label shouldBe label
            UndineException.NotFound(kind, "x").message shouldBe "$label 을(를) 찾을 수 없습니다: 'x'"
        }
    }

    test("서브모듈·워크트리 NotFound 메시지에 해당 라벨이 들어간다") {
        UndineException.NotFound(UndineException.NotFound.Kind.SUBMODULE, "libs/core")
            .message shouldBe "서브모듈 을(를) 찾을 수 없습니다: 'libs/core'"
        UndineException.NotFound(UndineException.NotFound.Kind.WORKTREE, "feature-a")
            .message shouldBe "워크트리 을(를) 찾을 수 없습니다: 'feature-a'"
    }

    test("AuthenticationMethod 는 SSH·HTTPS 두 값으로 닫혀 있다") {
        // 저장만 하는 값이다 — 원격 인증 연결은 UND-37 이후 티켓이 한다.
        AuthenticationMethod.entries.map { it.name } shouldContainExactly listOf("SSH", "HTTPS")
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
