package dev.undine.presentation.toolbar

import dev.undine.application.toolbar.FastForwardOutcome
import dev.undine.application.toolbar.FastForwardRefusal
import dev.undine.domain.CommitId
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.presentation.design.component.UndineToastTone
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.commonTranslations
import dev.undine.presentation.i18n.graphDragDropTranslations
import dev.undine.presentation.i18n.mergeTranslations
import dev.undine.presentation.i18n.toolbarTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotContainADigit

/** 원격이 응답에 실을 수 있는 값 — 어떤 문구에도 새어 나오면 안 된다. */
private const val REMOTE_URL = "https://ghp_secrettoken@github.example.com/team/undine.git"

/** 지목 조작의 대상. 긴 형식으로 넣어 문장이 `refs/heads/` 를 그대로 보여주지 않는지도 함께 본다. */
private val TARGET = RefName("refs/heads/feature")
private val MOVED_TO = CommitId.of("c".repeat(40))

/**
 * 결과 → 사용자 문구·톤 변환. 화면 렌더링 없이 검증한다 —
 * "무엇을 알리는가"는 배치가 아니라 이 매핑의 책임이다.
 */
class RemoteToolbarMessagesSpec : FunSpec({

    // 이력 기록 실패 안내는 그래프 조작이 쓰는 문장을 그대로 읽으므로 그 네임스페이스도 함께 싣는다.
    val catalog = StringCatalog(
        translations = mergeTranslations(
            listOf(commonTranslations, toolbarTranslations, graphDragDropTranslations),
        ),
        defaultLocale = DEFAULT_LOCALE,
    )
    val strings = catalog.stringsFor(DEFAULT_LOCALE, devBuild = false)

    test("fetch 성공은 갱신된 원격 참조 수를 알린다") {
        val message = remoteOperationMessage(strings, RemoteOperationOutcome.Fetched(refCount = 3))

        message.text shouldContain "3"
        message.tone shouldBe UndineToastTone.NEUTRAL
    }

    test("push 성공 문구는 계약에 없는 갱신 건수를 주장하지 않는다") {
        val message = remoteOperationMessage(strings, RemoteOperationOutcome.Pushed(force = false))

        message.text.shouldNotContainADigit()
        message.tone shouldBe UndineToastTone.NEUTRAL
    }

    test("force push 성공은 원격 이력을 덮어썼다는 사실을 알린다") {
        val forced = remoteOperationMessage(strings, RemoteOperationOutcome.Pushed(force = true))
        val plain = remoteOperationMessage(strings, RemoteOperationOutcome.Pushed(force = false))

        forced.text shouldNotBe plain.text
    }

    test("non-fast-forward 거절은 오류 톤이 아니라 pull 후 재시도 안내로 나온다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.PushRejected(PushResult.RejectReason.NON_FAST_FORWARD),
        )

        message.tone shouldNotBe UndineToastTone.ERROR
        message.text shouldContain "pull"
    }

    test("원격 거절은 사용자가 확인할 대상을 알린다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.PushRejected(PushResult.RejectReason.REMOTE_REJECTED),
        )

        message.text shouldNotBe ""
        message.tone shouldBe UndineToastTone.ERROR
    }

    test("인증 실패 문구에 자격증명·원격 URL 이 새어 나오지 않는다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.Failed(RemoteOperation.PUSH, RemoteFailureKind.AUTHENTICATION),
        )

        message.text shouldNotContain "ghp_"
        message.text shouldNotContain "github.example.com"
        message.text shouldNotContain "undine.git"
        message.text shouldNotContain REMOTE_URL
        message.text shouldContain "자격증명"
    }

    test("실패 종류마다 사용자가 취할 행동이 다른 문구로 나온다") {
        val texts = RemoteFailureKind.entries.map {
            remoteOperationMessage(strings, RemoteOperationOutcome.Failed(RemoteOperation.PULL, it)).text
        }

        texts.toSet().size shouldBe RemoteFailureKind.entries.size
        texts.forEach { it shouldNotBe "" }
    }

    /*
     * 지목 조작 결과의 문구는 **글자로 못박는다.** 기대값을 다시 getter 로 읽으면 문장이 어떻게
     * 바뀌어도 통과해 아무것도 지키지 못한다 (결정 D13).
     */

    test("지목 올리기 성공은 어느 브랜치를 올렸는지 이름으로 말한다") {
        val message = remoteOperationMessage(strings, RemoteOperationOutcome.BranchPushed(TARGET))

        message.text shouldBe "feature 브랜치를 원격에 올렸습니다."
        message.tone shouldBe UndineToastTone.NEUTRAL
    }

    test("확인을 지난 지목 덮어쓰기는 올리기와 다른 문장으로 나온다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.BranchPushed(TARGET, force = true),
        )

        message.text shouldBe "feature 브랜치로 원격 이력을 덮어썼습니다."
        message.tone shouldBe UndineToastTone.NEUTRAL
    }

    test("빨리 감기 성공은 체크아웃과 워킹트리가 그대로임을 함께 알린다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.BranchPulled(
                FastForwardOutcome.FastForwarded(TARGET, MOVED_TO, undoRecordFailure = null),
            ),
        )

        message.text shouldBe
            "feature 브랜치를 원격 위치로 빨리 감았습니다. 체크아웃과 워킹트리는 그대로입니다."
        message.tone shouldBe UndineToastTone.NEUTRAL
    }

    test("이력 기록만 실패한 빨리 감기는 되돌릴 수 없다는 사실과 복구 경로까지 알린다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.BranchPulled(
                FastForwardOutcome.FastForwarded(
                    TARGET,
                    MOVED_TO,
                    undoRecordFailure = UndineException.GitOperationFailed("undo.record"),
                ),
            ),
        )

        message.text shouldBe
            "feature 브랜치를 원격 위치로 빨리 감았습니다. 체크아웃과 워킹트리는 그대로입니다. " +
            "조작은 적용됐지만 되돌리기(Undo) 기록에 실패했습니다. " +
            "이 변경은 Undo 목록에 없으니 reflog 화면에서 이전 지점을 찾으세요."
        // 되돌릴 수 있다고 믿게 두지 않는다 — 성공과 같은 중립 톤이면 사실이 묻힌다 (결정 D16).
        message.tone shouldBe UndineToastTone.WARNING
    }

    test("이미 원격과 같은 위치면 실패가 아니라 중립 안내로 나온다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.BranchPulled(FastForwardOutcome.AlreadyUpToDate(TARGET)),
        )

        message.text shouldBe "feature 브랜치는 이미 원격과 같은 위치입니다."
        message.tone shouldBe UndineToastTone.NEUTRAL
    }

    test("빨리 감기 거부는 사유마다 다른 문장과 경고 톤으로 나온다") {
        val expected = mapOf(
            FastForwardRefusal.NOT_FAST_FORWARD to
                "feature 브랜치는 원격과 갈라져 빨리 감을 수 없습니다. 체크아웃한 뒤 가져와 병합으로 합치세요.",
            FastForwardRefusal.NO_UPSTREAM to "feature 브랜치의 추적 원격을 찾지 못해 받지 않았습니다.",
            FastForwardRefusal.CURRENT_BRANCH to
                "feature 브랜치는 지금 체크아웃돼 있습니다. 툴바의 가져와 병합을 쓰세요.",
        )

        // 사유가 늘면 이 표가 비어 실패한다 — 새 거부가 문구 없이 지나가지 않는다.
        FastForwardRefusal.entries.forEach { reason ->
            val message = remoteOperationMessage(
                strings,
                RemoteOperationOutcome.BranchPulled(FastForwardOutcome.Refused(TARGET, reason)),
            )

            message.text shouldBe expected.getValue(reason)
            message.tone shouldBe UndineToastTone.WARNING
        }
    }

    test("취소는 실패가 아니라 중립 안내로 나온다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.Cancelled(RemoteOperation.FETCH),
        )

        message.tone shouldBe UndineToastTone.NEUTRAL
    }

    test("push·pull 취소는 이미 적용됐을 수 있음을 경고 톤으로 알린다") {
        val pushCancelled = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.Cancelled(RemoteOperation.PUSH),
        )
        val pullCancelled = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.Cancelled(RemoteOperation.PULL),
        )
        val fetchCancelled = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.Cancelled(RemoteOperation.FETCH),
        )

        pushCancelled.tone shouldBe UndineToastTone.WARNING
        pullCancelled.tone shouldBe UndineToastTone.WARNING
        setOf(pushCancelled.text, pullCancelled.text, fetchCancelled.text).size shouldBe 3
    }

    test("force push 취소는 백업 참조로 되돌리는 경로를 알린다") {
        val message = remoteOperationMessage(
            strings,
            RemoteOperationOutcome.Cancelled(RemoteOperation.PUSH, forcePush = true),
        )

        message.tone shouldBe UndineToastTone.WARNING
        message.text shouldContain "refs/undine/force-push-backup"
        message.text shouldNotBe
            remoteOperationMessage(strings, RemoteOperationOutcome.Cancelled(RemoteOperation.PUSH)).text
    }
})
