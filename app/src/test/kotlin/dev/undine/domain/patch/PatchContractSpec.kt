package dev.undine.domain.patch

import dev.undine.domain.CommitId
import dev.undine.domain.Person
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val COMMIT_A = "1111111111111111111111111111111111111111"
private const val COMMIT_B = "2222222222222222222222222222222222222222"

/**
 * 공개 계약의 **모양**을 고정한다. 여기서 깨지면 UND-47 이 이어받을 타입이 바뀐 것이다.
 *
 * 동작이 아니라 타입을 보는 스펙이므로 저장소를 만들지 않는다 — 실제 Git 동작은
 * `PatchGatewayImplSpec` 이 임시 저장소로 검증한다.
 */
class PatchContractSpec : FunSpec({

    test("커밋 범위의 from 이 null 이면 루트부터를 뜻한다") {
        val fromRoot = CommitRange(from = null, to = CommitId.of(COMMIT_B))

        fromRoot.from.shouldBeNull()
        fromRoot.to shouldBe CommitId.of(COMMIT_B)
    }

    test("커밋 하나도 범위 하나로 표현된다") {
        val single = CommitRange(from = CommitId.of(COMMIT_A), to = CommitId.of(COMMIT_B))

        single.from shouldBe CommitId.of(COMMIT_A)
        single.to shouldBe CommitId.of(COMMIT_B)
    }

    test("내보내기 범위는 세 값을 가진 enum 이다") {
        WorkingTreeScope.entries shouldContainExactly listOf(
            WorkingTreeScope.STAGED,
            WorkingTreeScope.UNSTAGED,
            WorkingTreeScope.ALL,
        )
    }

    test("적용 모드는 Boolean 조합이 아니라 세 변이다") {
        val modes: List<ApplyMode> = listOf(
            ApplyMode.WorkingTreeOnly,
            ApplyMode.Index,
            ApplyMode.CreateCommit(Person("Undine Tester", "tester@undine.dev"), "메시지"),
        )

        modes.map { mode ->
            when (mode) {
                is ApplyMode.WorkingTreeOnly -> "workingTree"
                is ApplyMode.Index -> "index"
                is ApplyMode.CreateCommit -> "commit"
            }
        } shouldContainExactly listOf("workingTree", "index", "commit")
    }

    test("커밋 생성 모드만 작성자·메시지를 싣는다") {
        val commitMode = ApplyMode.CreateCommit(author = null, message = null)

        commitMode.author.shouldBeNull()
        commitMode.message.shouldBeNull()
    }

    test("적용 결과는 성공·충돌·미지원·변경없음을 한 타입으로 표현한다") {
        val outcomes: List<ApplyOutcome> = listOf(
            ApplyOutcome.Applied(listOf("a.txt")),
            ApplyOutcome.Conflicted(listOf("a.txt")),
            ApplyOutcome.Unsupported(UnsupportedReason.THREE_WAY_REQUIRED),
            ApplyOutcome.NoChange,
        )

        outcomes.map { outcome ->
            when (outcome) {
                is ApplyOutcome.Applied -> "applied:${outcome.paths}"
                is ApplyOutcome.Conflicted -> "conflicted:${outcome.paths}"
                is ApplyOutcome.Unsupported -> "unsupported:${outcome.reason}"
                ApplyOutcome.NoChange -> "noChange"
            }
        } shouldContainExactly listOf(
            "applied:[a.txt]",
            "conflicted:[a.txt]",
            "unsupported:THREE_WAY_REQUIRED",
            "noChange",
        )
    }

    test("미지원 사유는 자유 문자열이 아니라 열거된 타입이다") {
        val reason: UnsupportedReason = ApplyOutcome.Unsupported(UnsupportedReason.REVERSE_NOT_PURE_HUNK).reason

        reason.shouldBeInstanceOf<UnsupportedReason>()
        UnsupportedReason.entries shouldContainExactly listOf(
            UnsupportedReason.THREE_WAY_REQUIRED,
            UnsupportedReason.REVERSE_NOT_PURE_HUNK,
        )
    }

    test("내보내기는 커밋별과 통합본을 함께 담는다") {
        val export = PatchExport(
            perCommit = listOf(CommitPatch(CommitId.of(COMMIT_A), "patch-a".toByteArray())),
            combined = "combined".toByteArray(),
        )

        export.perCommit.single().commit shouldBe CommitId.of(COMMIT_A)
        export.perCommit.single().bytes.toString(Charsets.UTF_8) shouldBe "patch-a"
        export.combined.toString(Charsets.UTF_8) shouldBe "combined"
    }

    test("내보내기 결과의 동치성은 바이트 내용으로 판단한다") {
        val first = PatchExport(listOf(CommitPatch(CommitId.of(COMMIT_A), byteArrayOf(1, 2))), byteArrayOf(3))
        val second = PatchExport(listOf(CommitPatch(CommitId.of(COMMIT_A), byteArrayOf(1, 2))), byteArrayOf(3))

        first shouldBe second
        first.hashCode() shouldBe second.hashCode()
    }
})
