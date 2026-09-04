package dev.undine.bench

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.commitFile
import dev.undine.infrastructure.git.repository.initBareRepository
import dev.undine.infrastructure.git.repository.initRepository
import dev.undine.infrastructure.git.worktree.WorktreeGatewayImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File

private const val LINKED_BRANCH = "bench-linked"

/**
 * 벤치 스펙이 **언제 도는지**를 고정한다.
 *
 * 활성화 판정이 틀리면 두 방향으로 조용히 손해가 난다 — 참으로 잘못 판정하면 일반 `build` 가
 * 대형 저장소를 찾다 실패하고, 거짓으로 잘못 판정하면 벤치 저장소를 만들어 두고 돌렸는데도
 * 아무것도 측정되지 않은 채 초록불이 된다. 판정은 순수 함수로 떼어 두고 여기서 본다.
 *
 * 이 스펙 자체는 저장소를 만들지 않으므로 일반 `build` 에 비용을 더하지 않는다.
 */
class BenchRepositoryLocatorSpec : FunSpec({

    test("환경 변수가 없으면 벤치 저장소 경로가 없다") {
        benchRepositoryPath { null }.shouldBeNull()
    }

    test("환경 변수가 공백뿐이면 벤치 저장소 경로가 없다") {
        benchRepositoryPath { "   " }.shouldBeNull()
    }

    test("환경 변수가 가리키는 경로가 없으면 벤치 저장소 경로가 없다") {
        val missing = File(tempdir(), "absent-repository")

        benchRepositoryPath { missing.path }.shouldBeNull()
    }

    test("경로는 있지만 Git 저장소가 아니면 벤치 저장소 경로가 없다") {
        val plainDirectory = tempdir()

        benchRepositoryPath { plainDirectory.path }.shouldBeNull()
    }

    test("실제 Git 저장소를 가리키면 그 경로를 돌려준다") {
        val repository = tempdir()
        initRepository(repository).use { git -> git.commitFile("a.txt", "a\n", "first") }

        benchRepositoryPath { repository.path }?.toString() shouldBe repository.path
    }

    // 아래 두 경우는 `.git` 이 존재하는지만 보면 전부 "저장소 있음" 으로 통과한다. 그러면 스펙이
    // 켜진 뒤 저장소를 열 때 터지고, 사용자에게는 "건너뜀" 이 아니라 "실패" 로만 보인다.
    test("빈 .git 디렉터리만 있으면 벤치 저장소 경로가 없다") {
        val repository = tempdir()
        File(repository, ".git").mkdir()

        benchRepositoryPath { repository.path }.shouldBeNull()
    }

    test("gitdir 이 손상돼 HEAD 가 없으면 벤치 저장소 경로가 없다") {
        val repository = tempdir()
        initRepository(repository).use { git -> git.commitFile("a.txt", "a\n", "first") }
        File(repository, ".git/HEAD").delete() shouldBe true

        benchRepositoryPath { repository.path }.shouldBeNull()
    }

    // `.git` 이 디렉터리인지만 물으면 여기서 거짓이 된다. 그러면 `GitAccess` 가 멀쩡히 여는
    // 저장소를 두고 벤치가 조용히 건너뛴다 — 사용자에게는 아무 일도 일어나지 않은 것으로 보인다.
    test("linked worktree 를 가리켜도 벤치 저장소 경로를 돌려준다") {
        val main = tempdir()
        initRepository(main).use { git ->
            git.commitFile("a.txt", "a\n", "first")
            git.branchCreate().setName(LINKED_BRANCH).call()
        }
        val linked = File(tempdir(), "linked")
        val gitAccess = GitAccess()
        gitAccess.open(RepositoryPath(main.absolutePath)) { }
        try {
            WorktreeGatewayImpl(gitAccess).add(RepositoryPath(linked.absolutePath), RefName(LINKED_BRANCH))
        } finally {
            gitAccess.close()
        }

        File(linked, ".git").isFile shouldBe true
        benchRepositoryPath { linked.path }?.toString() shouldBe linked.path
    }

    // 베어 저장소는 JGit 이 멀쩡히 연다. 열림만 보고 켜면 벤치가 건너뛰는 대신 `GitAccess` 의
    // BARE_REPOSITORY 거절로 **실패**한다 — 준비물이 틀렸다는 신호가 결함처럼 보인다.
    test("베어 저장소를 가리키면 벤치 저장소 경로가 없다") {
        val bare = File(tempdir(), "bench.git")
        initBareRepository(bare).close()

        benchRepositoryPath { bare.path }.shouldBeNull()
    }

    // 경로가 될 수 없는 문자열에서 예외가 새면 `@EnabledIf` 평가 자체가 터진다. 저장소를 준비하지
    // 않은 일반 build 까지 건너뜀이 아니라 실패로 보인다.
    test("경로로 만들 수 없는 문자열이면 벤치 저장소 경로가 없다") {
        benchRepositoryPath { "bench\u0000repo" }.shouldBeNull()
    }

    test("활성화 조건은 벤치 저장소 경로가 있을 때만 참이다") {
        BenchRepositoryPresent().enabled(BenchRepositoryLocatorSpec::class) shouldBe
            (benchRepositoryPath() != null)
    }
})
