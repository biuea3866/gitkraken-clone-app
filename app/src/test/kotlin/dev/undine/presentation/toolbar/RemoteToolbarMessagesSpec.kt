package dev.undine.presentation.toolbar

import dev.undine.domain.PushResult
import dev.undine.presentation.design.component.UndineToastTone
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.commonTranslations
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

/**
 * 결과 → 사용자 문구·톤 변환. 화면 렌더링 없이 검증한다 —
 * "무엇을 알리는가"는 배치가 아니라 이 매핑의 책임이다.
 */
class RemoteToolbarMessagesSpec : FunSpec({

    val catalog = StringCatalog(
        translations = mergeTranslations(listOf(commonTranslations, toolbarTranslations)),
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
