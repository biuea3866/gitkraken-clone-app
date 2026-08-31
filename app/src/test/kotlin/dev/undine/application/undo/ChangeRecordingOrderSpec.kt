package dev.undine.application.undo

import dev.undine.application.staging.CommitStagedUseCase
import dev.undine.domain.CommitResult
import dev.undine.domain.StagingGateway
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.commitId
import dev.undine.testsupport.recorderOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import dev.undine.domain.undo.UndoStack

class ChangeRecordingOrderSpec : FunSpec({

    test("첫 변경의 기록이 멈춰 있는 동안 다음 변경은 시작하지 않아 Undo 순서가 보존된다") {
        val firstHead = commitId(11)
        val secondHead = commitId(12)
        val firstRecordEntered = CompletableDeferred<Unit>()
        val releaseFirstRecord = CompletableDeferred<Unit>()
        val secondChangeStarted = CompletableDeferred<Unit>()
        val stack = UndoStack()
        val recorder = spyk(recorderOf(stack, changeRecordingOrder = GitAccess()))
        var records = 0
        coEvery { recorder.record(any(), any(), any(), any()) } coAnswers {
            records += 1
            if (records == 1) {
                firstRecordEntered.complete(Unit)
                releaseFirstRecord.await()
            }
            callOriginal()
        }
        val staging = io.mockk.mockk<StagingGateway>().also {
            coEvery { it.commit("first") } returns
                CommitResult(firstHead, previousHead = commitId(10), baseline = baselineOf(firstHead))
            coEvery { it.commit("second") } coAnswers {
                secondChangeStarted.complete(Unit)
                CommitResult(secondHead, previousHead = firstHead, baseline = baselineOf(secondHead))
            }
        }
        val useCase = CommitStagedUseCase(staging, recorder)

        coroutineScope {
            val first = async { useCase.execute("first") }
            firstRecordEntered.await()
            val second = async { useCase.execute("second") }

            withTimeoutOrNull(100) { secondChangeStarted.await() }.shouldBeNull()

            releaseFirstRecord.complete(Unit)
            first.await()
            second.await()
        }

        stack.history().map { it.targetLabel } shouldContainExactly listOf(secondHead.value, firstHead.value)
    }
})
