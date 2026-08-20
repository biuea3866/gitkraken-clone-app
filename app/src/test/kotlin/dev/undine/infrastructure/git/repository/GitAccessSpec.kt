package dev.undine.infrastructure.git.repository

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** 겹침을 관측할 수 있을 만큼만 임계 구역에 머무른다. */
private const val CRITICAL_SECTION_MILLIS = 2L

private const val CONCURRENT_CALLERS = 16

class GitAccessSpec : FunSpec({

    test("동시 호출에도 Repository 접근이 겹치지 않는다") {
        val directory = tempdir()
        initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
        val gitAccess = GitAccess()
        gitAccess.open(RepositoryPath(directory.path)) { }
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        coroutineScope {
            repeat(CONCURRENT_CALLERS) {
                launch(Dispatchers.Default) {
                    gitAccess.withRepository { repository ->
                        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        repository.repositoryState
                        Thread.sleep(CRITICAL_SECTION_MILLIS)
                        active.decrementAndGet()
                    }
                }
            }
        }

        maxActive.get() shouldBe 1
        gitAccess.close()
    }

    test("열기 전 withRepository 는 StateViolation 을 던진다") {
        val failure = shouldThrow<UndineException.StateViolation> {
            GitAccess().withRepository { it.repositoryState }
        }

        failure.detail shouldBe REPOSITORY_NOT_OPEN
    }
})
