package dev.undine.application.welcome

import dev.undine.domain.RepositoryPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

private val ALPHA = RepositoryPath("/tmp/alpha")
private val BETA = RepositoryPath("/tmp/beta")
private val GAMMA = RepositoryPath("/tmp/gamma")

/** 최근 목록 순서 규칙 — 앞이 최신이다. 20개 절단은 `SettingsGateway.save` 책임이라 여기서 다루지 않는다. */
class RecentRepositoriesSpec : FunSpec({

    test("새 경로는 목록 맨 앞에 온다") {
        listOf(ALPHA, BETA).withMostRecent(GAMMA) shouldContainExactly listOf(GAMMA, ALPHA, BETA)
    }

    test("이미 있는 경로를 다시 열면 중복 없이 맨 앞으로 옮겨진다") {
        listOf(ALPHA, BETA, GAMMA).withMostRecent(BETA) shouldContainExactly listOf(BETA, ALPHA, GAMMA)
    }

    test("빈 목록에 첫 경로를 넣으면 그 하나만 남는다") {
        emptyList<RepositoryPath>().withMostRecent(ALPHA) shouldContainExactly listOf(ALPHA)
    }

    test("제거하면 그 경로만 빠지고 나머지 순서는 유지된다") {
        listOf(ALPHA, BETA, GAMMA).without(BETA) shouldContainExactly listOf(ALPHA, GAMMA)
    }

    test("목록에 없는 경로를 제거해도 목록이 그대로다") {
        listOf(ALPHA, BETA).without(GAMMA) shouldContainExactly listOf(ALPHA, BETA)
    }
})
