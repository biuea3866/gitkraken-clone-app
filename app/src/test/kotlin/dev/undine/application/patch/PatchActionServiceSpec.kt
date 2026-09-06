package dev.undine.application.patch

import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.CommitRange
import dev.undine.domain.patch.PatchExport
import dev.undine.domain.patch.PatchGateway
import dev.undine.domain.patch.WorkingTreeScope
import dev.undine.domain.RefName
import dev.undine.testsupport.HistoryRequest
import dev.undine.testsupport.RecordingHistoryGateway
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Patch 화면의 application 경계 — 최근 커밋 조회·구간 변환·게이트웨이 위임.
 *
 * 게이트웨이 대역은 도메인 interface 의 대역이다. JGit 연산 자체는 `infrastructure/git/patch` 의
 * 실제 임시 저장소 스펙이 검증한다 (testing 규칙 1).
 */
class PatchActionServiceSpec : FunSpec({

    test("최근 커밋은 HEAD 기준 한 페이지 100건을 읽는다") {
        val history = RecordingHistoryGateway(commits = List(150) { commit(it + 1) })
        val service = PatchActionService(FakePatchGateway(), history)

        service.loadRecentCommits().size shouldBe PATCH_COMMIT_PAGE_SIZE
        history.requests.single() shouldBe HistoryRequest(listOf(RefName("HEAD")), 0, PATCH_COMMIT_PAGE_SIZE)
    }

    test("선택이 비면 커밋 범위를 만들지 않아 화면이 생성을 막을 수 있다") {
        commitRangeOf(emptyList()).shouldBeNull()
    }

    test("커밋 하나를 고르면 그 커밋의 첫 부모부터 그 커밋까지가 된다") {
        commitRangeOf(listOf(commit(5, 4))).shouldNotBeNull() shouldBe
            CommitRange(from = commitId(4), to = commitId(5))
    }

    test("연속 구간은 가장 최신을 to 로, 가장 오래된 것의 첫 부모를 from 으로 삼는다") {
        val selected = listOf(commit(9, 8), commit(8, 7), commit(7, 6))

        commitRangeOf(selected) shouldBe CommitRange(from = commitId(6), to = commitId(9))
    }

    test("구간 끝이 최초 커밋이면 from 이 없어 루트부터가 된다") {
        commitRangeOf(listOf(commit(2, 1), commit(1))) shouldBe CommitRange(from = null, to = commitId(2))
    }

    test("구간 끝이 병합 커밋이면 첫 부모만 기준으로 삼는다") {
        val merge = commit(3, 2, 90)

        commitRangeOf(listOf(commit(4, 3), merge)) shouldBe CommitRange(from = commitId(2), to = commitId(4))
    }

    test("현재 변경·커밋 범위 생성과 dry-run·apply 를 그대로 게이트웨이에 위임한다") {
        val gateway = FakePatchGateway()
        val service = PatchActionService(gateway, RecordingHistoryGateway())
        val patch = "diff --git a/a.kt b/a.kt\n".toByteArray()

        service.exportWorkingTree(WorkingTreeScope.STAGED)
        service.exportCommits(CommitRange(from = null, to = commitId(1)))
        service.dryRun(patch, ApplyMode.WorkingTreeOnly) shouldBe ApplyOutcome.NoChange
        service.apply(patch, ApplyMode.Index) shouldBe ApplyOutcome.NoChange

        gateway.scopes shouldContainExactly listOf(WorkingTreeScope.STAGED)
        gateway.ranges shouldContainExactly listOf(CommitRange(from = null, to = commitId(1)))
        gateway.dryRuns shouldContainExactly listOf(ApplyMode.WorkingTreeOnly)
        gateway.applies shouldContainExactly listOf(ApplyMode.Index)
    }

    test("미리보기와 커밋 메타데이터는 게이트웨이를 부르지 않고 패치 바이트로 답한다") {
        val gateway = FakePatchGateway()
        val service = PatchActionService(gateway, RecordingHistoryGateway())

        service.preview("diff --git a/a.kt b/a.kt\n".toByteArray())
        service.commitMetadataOf("From: A B <a@b.c>\nSubject: 제목\n".toByteArray())
            .author.shouldNotBeNull().email shouldBe "a@b.c"

        gateway.dryRuns.shouldBeEmpty()
        gateway.applies.shouldBeEmpty()
    }
})

private class FakePatchGateway : PatchGateway {

    val scopes = mutableListOf<WorkingTreeScope>()
    val ranges = mutableListOf<CommitRange>()
    val dryRuns = mutableListOf<ApplyMode>()
    val applies = mutableListOf<ApplyMode>()

    override suspend fun export(range: CommitRange): PatchExport {
        ranges += range
        return PatchExport(perCommit = emptyList(), combined = ByteArray(0))
    }

    override suspend fun export(scope: WorkingTreeScope): PatchExport {
        scopes += scope
        return PatchExport(perCommit = emptyList(), combined = ByteArray(0))
    }

    override suspend fun dryRun(patch: ByteArray, mode: ApplyMode): ApplyOutcome {
        dryRuns += mode
        return ApplyOutcome.NoChange
    }

    override suspend fun apply(patch: ByteArray, mode: ApplyMode): ApplyOutcome {
        applies += mode
        return ApplyOutcome.NoChange
    }

    override suspend fun applyReversed(patch: ByteArray): ApplyOutcome = ApplyOutcome.NoChange
}
