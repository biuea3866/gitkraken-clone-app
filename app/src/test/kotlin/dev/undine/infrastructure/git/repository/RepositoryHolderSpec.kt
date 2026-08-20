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

private fun committedRepositoryAt(directory: File): File {
    initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
    return directory
}

class RepositoryHolderSpec : FunSpec({

    test("열기 전에는 current 가 null 이다") {
        countingHolder().current() shouldBe null
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
        holder.current() shouldBe secondRepository
        holder.close()
    }

    test("close 는 현재 핸들을 닫고 current 를 비운다") {
        val directory = committedRepositoryAt(tempdir())
        val holder = countingHolder()
        val repository = holder.open(RepositoryPath(directory.path))

        holder.close()

        repository.closeCount() shouldBe 1
        holder.current() shouldBe null
    }

    test("열지 않은 상태의 close 는 아무 일도 하지 않는다") {
        countingHolder().close()
    }
})
