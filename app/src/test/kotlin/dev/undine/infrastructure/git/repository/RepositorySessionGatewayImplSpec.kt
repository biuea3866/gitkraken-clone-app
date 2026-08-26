package dev.undine.infrastructure.git.repository

import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositorySessionKey
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 실제 JGit 저장소를 통해 탭 세션 adapter의 열기·회수·되돌리기·전체 종료 경로를 검증한다. */
class RepositorySessionGatewayImplSpec : FunSpec({

    test("서로 다른 실제 저장소를 열고 활성 세션을 회수한 뒤 전체 종료한다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val access = GitAccess()
        val gateway = RepositorySessionGatewayImpl(access)

        val firstKey = gateway.transition { it.open(RepositoryPath(firstDirectory.path)) }
        val secondKey = gateway.transition { it.open(RepositoryPath(secondDirectory.path)) }
        access.withRepository { it.workTree.canonicalFile } shouldBe secondDirectory.canonicalFile

        gateway.transition { it.release(secondKey) }
        shouldThrow<UndineException.StateViolation> { access.withRepository { it.repositoryState } }

        gateway.transition { it.open(RepositoryPath(firstDirectory.path)) } shouldBe firstKey
        gateway.transition { it.close() }
        shouldThrow<UndineException.StateViolation> { access.withRepository { it.repositoryState } }
    }

    test("경로 표기가 달라도 같은 저장소면 같은 세션 키를 준다") {
        val directory = committedRepositoryAt(tempdir())
        val gateway = RepositorySessionGatewayImpl(GitAccess())

        val key = gateway.transition { it.open(RepositoryPath(directory.path)) }
        val aliasKey = gateway.transition { it.open(RepositoryPath(File(directory, ".").path)) }

        aliasKey shouldBe key
        gateway.transition { it.close() }
    }

    test("restoreSessions 는 목록 밖 세션을 해제하고 목록 안 세션을 다시 열어 active 로 만든다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val access = GitAccess()
        val gateway = RepositorySessionGatewayImpl(access)
        val firstKey = gateway.transition { it.open(RepositoryPath(firstDirectory.path)) }
        val secondKey = gateway.transition { it.open(RepositoryPath(secondDirectory.path)) }
        gateway.transition { it.release(firstKey) }

        val restored = gateway.transition { it.restoreSessions(listOf(firstKey), active = firstKey) }

        restored shouldBe listOf(firstKey)
        // 다시 연 저장소가 활성이고, 목록 밖이던 두 번째 세션은 해제됐다.
        access.withRepository { it.workTree.canonicalFile } shouldBe firstDirectory.canonicalFile
        secondKey shouldNotBe firstKey
        gateway.transition { it.restoreSessions(emptyList(), active = null) } shouldBe emptyList()
        shouldThrow<UndineException.StateViolation> { access.withRepository { it.repositoryState } }
        gateway.transition { it.close() }
    }

    test("restoreSessions 는 사라진 세션을 실패가 아니라 결과 제외로 다룬다") {
        val directory = committedRepositoryAt(tempdir())
        val access = GitAccess()
        val gateway = RepositorySessionGatewayImpl(access)
        val key = gateway.transition { it.open(RepositoryPath(directory.path)) }
        val vanished = RepositorySessionKey(File(tempdir(), "vanished").path)

        val restored = gateway.transition { it.restoreSessions(listOf(key, vanished), active = vanished) }

        restored shouldBe listOf(key)
        // 되살리지 못한 세션을 active 로 지정했으므로 활성 세션은 비어 있어야 한다.
        shouldThrow<UndineException.StateViolation> { access.withRepository { it.repositoryState } }
        gateway.transition { it.close() }
    }

    test("존재하지 않는 세션 경로는 InvalidRepositoryPath로 번역된다") {
        val gateway = RepositorySessionGatewayImpl(GitAccess())
        val missing = File(tempdir(), "missing")

        val failure = shouldThrow<UndineException.InvalidRepositoryPath> {
            gateway.transition { it.open(RepositoryPath(missing.path)) }
        }

        failure.reason shouldBe UndineException.InvalidRepositoryPath.Reason.NOT_FOUND
    }

    test("transition 은 앞선 전이가 끝날 때까지 다른 전이를 들이지 않는다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val gateway = RepositorySessionGatewayImpl(GitAccess())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        coroutineScope {
            val holding = async(Dispatchers.Default) {
                gateway.transition { sessions ->
                    sessions.open(RepositoryPath(firstDirectory.path))
                    entered.complete(Unit)
                    // 실제 전이의 설정 저장처럼, Git 이 아닌 대기가 구역 안에서 일어난다.
                    release.await()
                }
            }
            entered.await()
            val following = async(Dispatchers.Default) {
                gateway.transition { it.open(RepositoryPath(secondDirectory.path)) }
            }
            withContext(Dispatchers.Default) { delay(200) }

            following.isCompleted shouldBe false
            release.complete(Unit)
            holding.await()
            following.await() shouldNotBe null
        }

        gateway.transition { it.close() }
    }
})

private fun committedRepositoryAt(directory: File): File {
    initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
    return directory
}
