package dev.undine.infrastructure.git.repository

import dev.undine.domain.RepositoryPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 닫힘을 관측하기 위한 **실제** `FileRepository` 다 — Mock 이 아니다.
 * JGit `Repository` 는 닫힘 여부를 노출하지 않으므로 `close()` 호출 횟수만 센다.
 */
private class CloseCountingRepository(gitDirectory: File) : FileRepository(gitDirectory) {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
        super.close()
    }
}

/** 세션 키(정규화된 워킹트리 경로)를 받아 그 저장소의 닫힘 세는 핸들을 연다. */
private fun countingHolder(): RepositoryHolder =
    RepositoryHolder { workTree -> CloseCountingRepository(File(workTree.toFile(), Constants.DOT_GIT)) }

private fun Repository.closeCount(): Int = (this as CloseCountingRepository).closeCount

/**
 * 활성 세션의 핸들. 홀더는 **키만** 노출하므로(`GitAccess` 가 락을 잡기 전에 키로 실행 대상을
 * 고정한다 — UND-80) 여기서 잇는다.
 */
private fun RepositoryHolder.activeRepository(): Repository? = activeSessionKey()?.let { sessionAt(it) }

/** 세션 키로 꺼낸 핸들 — 방금 연 세션이므로 없으면 그 자체가 실패다. */
private fun Repository?.require(): Repository = requireNotNull(this) { "세션 핸들이 없습니다" }

private fun committedRepositoryAt(directory: File): File {
    initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
    return directory
}

class RepositoryHolderSpec : FunSpec({

    test("열기 전에는 활성 핸들이 없다") {
        countingHolder().activeRepository() shouldBe null
    }

    test("같은 저장소를 다시 열면 기존 핸들을 재사용한다") {
        val directory = committedRepositoryAt(tempdir())
        val holder = countingHolder()

        val first = holder.open(RepositoryPath(directory.path))
        val second = holder.open(RepositoryPath(directory.path))

        second shouldBe first
        first.closeCount() shouldBe 0
        holder.close()
    }

    test("경로 표기가 달라도 정규화된 같은 세션 키로 취급한다") {
        val directory = committedRepositoryAt(tempdir())
        val holder = countingHolder()

        val first = holder.open(RepositoryPath(directory.path))
        val second = holder.open(RepositoryPath(File(directory, ".").path))

        second shouldBe first
        first.closeCount() shouldBe 0
        holder.close()
    }

    test("심볼릭 링크로 열어도 같은 세션 키로 취급한다") {
        val directory = committedRepositoryAt(tempdir())
        val link = Files.createSymbolicLink(
            File(tempdir(), "link-to-repository").toPath(),
            directory.toPath(),
        )
        val holder = countingHolder()

        val first = holder.open(RepositoryPath(directory.path))
        val second = holder.open(RepositoryPath(link.toString()))

        second shouldBe first
        first.closeCount() shouldBe 0
        holder.close()
    }

    test("저장소를 전환하면 이전 핸들을 닫는다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val holder = countingHolder()

        val firstRepository = holder.open(RepositoryPath(firstDirectory.path))
        val secondRepository = holder.open(RepositoryPath(secondDirectory.path))

        secondRepository shouldNotBe firstRepository
        firstRepository.closeCount() shouldBe 1
        secondRepository.closeCount() shouldBe 0
        holder.activeRepository() shouldBe secondRepository
        holder.close()
    }

    test("close 는 현재 핸들을 닫고 활성 세션을 비운다") {
        val directory = committedRepositoryAt(tempdir())
        val holder = countingHolder()
        val repository = holder.open(RepositoryPath(directory.path))

        holder.close()

        repository.closeCount() shouldBe 1
        holder.activeRepository() shouldBe null
    }

    test("열지 않은 상태의 close 는 아무 일도 하지 않는다") {
        countingHolder().close()
    }

    test("여러 세션을 열고 LRU 대상만 회수할 수 있다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val holder = countingHolder()

        val firstKey = holder.openSession(RepositoryPath(firstDirectory.path))
        val first = holder.sessionAt(firstKey).require()
        val secondKey = holder.openSession(RepositoryPath(secondDirectory.path))
        val second = holder.sessionAt(secondKey).require()
        holder.releaseSession(firstKey)

        first.closeCount() shouldBe 1
        second.closeCount() shouldBe 0
        holder.activeRepository() shouldBe second
        holder.close()
    }

    test("경로 표기가 달라도 같은 세션 키를 돌려주므로 회수가 활성 핸들을 닫지 않는다") {
        val directory = committedRepositoryAt(tempdir())
        val other = committedRepositoryAt(tempdir())
        val holder = countingHolder()

        val key = holder.openSession(RepositoryPath(directory.path))
        val aliasKey = holder.openSession(RepositoryPath(File(directory, ".").path))
        val otherKey = holder.openSession(RepositoryPath(other.path))

        aliasKey shouldBe key
        holder.releaseSession(otherKey)
        // 별칭으로 연 탭을 회수해도 같은 저장소를 보는 활성 핸들이 살아 있어야 한다.
        holder.sessionAt(key).require().closeCount() shouldBe 0
        holder.close()
    }

    test("활성 세션을 회수하면 그 핸들만 닫고 활성 세션을 비운다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val holder = countingHolder()

        val firstKey = holder.openSession(RepositoryPath(firstDirectory.path))
        val first = holder.sessionAt(firstKey).require()
        val secondKey = holder.openSession(RepositoryPath(secondDirectory.path))
        val second = holder.sessionAt(secondKey).require()
        holder.releaseSession(secondKey)

        second.closeCount() shouldBe 1
        first.closeCount() shouldBe 0
        holder.activeRepository() shouldBe null
        holder.close()
        first.closeCount() shouldBe 1
    }

    test("활성 세션 키는 열기 전에 null 이고 회수하면 다시 비워진다") {
        val directory = committedRepositoryAt(tempdir())
        val holder = countingHolder()
        holder.activeSessionKey() shouldBe null

        val key = holder.openSession(RepositoryPath(directory.path))

        holder.activeSessionKey() shouldBe key
        holder.releaseSession(key)
        holder.activeSessionKey() shouldBe null
        holder.close()
    }

    test("close 는 열려 있는 모든 세션을 닫는다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val holder = countingHolder()

        val first = holder.sessionAt(holder.openSession(RepositoryPath(firstDirectory.path))).require()
        val second = holder.sessionAt(holder.openSession(RepositoryPath(secondDirectory.path))).require()
        holder.close()

        first.closeCount() shouldBe 1
        second.closeCount() shouldBe 1
        holder.activeRepository() shouldBe null
    }

    test("restoreSessions 는 목록 밖 세션을 회수하고 닫힌 세션을 다시 연다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val holder = countingHolder()

        val firstKey = holder.openSession(RepositoryPath(firstDirectory.path))
        val first = holder.sessionAt(firstKey).require()
        val secondKey = holder.openSession(RepositoryPath(secondDirectory.path))
        holder.releaseSession(secondKey)

        val restored = holder.restoreSessions(listOf(secondKey), active = secondKey)

        restored shouldBe listOf(secondKey)
        first.closeCount() shouldBe 1
        holder.sessionAt(firstKey) shouldBe null
        holder.activeRepository() shouldBe holder.sessionAt(secondKey)
        holder.close()
    }

    // 되살릴 수 없는 세션을 결과에서 빼는 경로는 RepositorySessionGatewayImplSpec 이 실제 저장소로 본다
    // — 여기 세는 홀더는 존재 검사 없는 FileRepository 를 쓰므로 그 분기를 재현하지 못한다.

    test("동시 열기·회수·해제가 홀더의 한 임계구역에서 안전하게 끝난다") {
        val firstDirectory = committedRepositoryAt(tempdir())
        val secondDirectory = committedRepositoryAt(tempdir())
        val firstPath = RepositoryPath(firstDirectory.path)
        val secondPath = RepositoryPath(secondDirectory.path)
        val holder = countingHolder()

        coroutineScope {
            repeat(20) { index ->
                launch(Dispatchers.Default) {
                    val path = if (index % 2 == 0) firstPath else secondPath
                    holder.releaseSession(holder.openSession(path))
                    holder.open(path)
                }
            }
        }

        // 마지막 활성 세션은 인터리빙이 정하므로 단정하지 않는다 — 여기서 보는 것은 손상 없이 끝나는가다.
        holder.activeRepository() shouldNotBe null
        holder.close()
        holder.activeRepository() shouldBe null
    }
})
