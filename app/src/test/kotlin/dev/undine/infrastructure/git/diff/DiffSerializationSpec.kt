package dev.undine.infrastructure.git.diff

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch

private const val ANY_COMMIT = "1234567890abcdef1234567890abcdef12345678"

/** 잠금을 우회하면 이 시간 안에 끝난다 — 파일 두 개짜리 저장소의 diff 는 밀리초 단위다. */
private const val BYPASS_WINDOW_MILLIS = 300L

/**
 * 공유 [GitAccess] 경계 밖에서 `Repository` 를 만지지 않는지 검증한다.
 *
 * JGit `Repository` 는 스레드 안전하지 않으므로 diff 가 자기 스레드에서 따로 핸들을 만지면
 * 다른 Gateway 호출과 겹쳐 저장소 읽기가 손상된다. 여기 두 테스트가 그 회귀를 막는다.
 */
class DiffSerializationSpec : FunSpec({

    test("다른 사용자가 임계 구역에 있는 동안 diff 호출은 진행되지 않는다") {
        initRepository(tempdir()).use { git ->
            git.writeFile("a.txt", "one\n")
            git.commitAll("first")
            git.writeFile("a.txt", "two\n")
            val second = git.commitAll("second")
            val gitAccess = git.sharedAccess()
            val gateway = DiffGatewayImpl(gitAccess)
            val occupied = CountDownLatch(1)
            val release = CountDownLatch(1)

            coroutineScope {
                launch(Dispatchers.IO) {
                    gitAccess.withRepository {
                        occupied.countDown()
                        release.await()
                    }
                }
                occupied.await()
                val blocked = async(Dispatchers.IO) { gateway.changedFiles(second.id(), 0) }

                withTimeoutOrNull(BYPASS_WINDOW_MILLIS) { blocked.await() }.shouldBeNull()
                release.countDown()
                blocked.await() shouldHaveSize 1
            }
        }
    }

    test("저장소가 열려 있지 않으면 네 diff 호출 모두 StateViolation 으로 멈춘다") {
        val gateway = DiffGatewayImpl(GitAccess())
        val commit = CommitId.of(ANY_COMMIT)

        shouldThrow<UndineException.StateViolation> { gateway.changedFiles(commit, 0) }
        shouldThrow<UndineException.StateViolation> { gateway.changedFilesStaged() }
        shouldThrow<UndineException.StateViolation> { gateway.changedFilesUnstaged() }
        shouldThrow<UndineException.StateViolation> { gateway.hunksOf(commit, "a.txt", 0) }
    }
})
