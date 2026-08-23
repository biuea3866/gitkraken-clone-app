package dev.undine.domain

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val TARGET_HASH = "1111111111111111111111111111111111111111"
private const val OTHER_HASH = "2222222222222222222222222222222222222222"

private val TARGET = CommitId.of(TARGET_HASH)
private val OTHER_TARGET = CommitId.of(OTHER_HASH)

class AmendConfirmationSpec : FunSpec({

    test("원격에 없는 대상은 확인 없이 고칠 수 있다") {
        shouldNotThrowAny {
            AmendConfirmation.NotRequired.validateFor(TARGET, existsOnRemote = false)
        }
    }

    test("원격에 있는 대상을 확인 없이 고치려 하면 확인 누락으로 거부한다") {
        val exception = shouldThrow<UndineException.AmendConfirmationRequired> {
            AmendConfirmation.NotRequired.validateFor(TARGET, existsOnRemote = true)
        }

        exception.reason shouldBe UndineException.AmendConfirmationRequired.Reason.NOT_CONFIRMED
        exception.target shouldBe TARGET
    }

    test("확인한 대상과 실행 대상이 같으면 원격에 있어도 고칠 수 있다") {
        shouldNotThrowAny {
            AmendConfirmation.ConfirmedRemoteTarget(TARGET).validateFor(TARGET, existsOnRemote = true)
        }
    }

    test("확인한 대상이 실행 대상과 다르면 낡은 확인으로 거부한다") {
        val exception = shouldThrow<UndineException.AmendConfirmationRequired> {
            AmendConfirmation.ConfirmedRemoteTarget(OTHER_TARGET).validateFor(TARGET, existsOnRemote = true)
        }

        exception.reason shouldBe UndineException.AmendConfirmationRequired.Reason.TARGET_MISMATCH
        exception.target shouldBe TARGET
    }

    test("대상이 바뀌었다면 그 사이 원격 미포함이 됐더라도 낡은 확인으로 거부한다") {
        shouldThrow<UndineException.AmendConfirmationRequired> {
            AmendConfirmation.ConfirmedRemoteTarget(OTHER_TARGET).validateFor(TARGET, existsOnRemote = false)
        }
    }

    test("preflight 는 대상 커밋과 원격 포함 여부를 함께 제공한다") {
        val preflight = AmendPreflight(target = TARGET, existsOnRemote = true)

        preflight.target shouldBe TARGET
        preflight.existsOnRemote shouldBe true
    }
})
