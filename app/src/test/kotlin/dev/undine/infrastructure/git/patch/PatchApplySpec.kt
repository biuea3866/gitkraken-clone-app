package dev.undine.infrastructure.git.patch

import dev.undine.domain.Person
import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.UnsupportedReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val PATCH_AUTHOR_NAME = "Patch Author"
private const val PATCH_AUTHOR_EMAIL = "patch@undine.dev"
private const val FALLBACK_MESSAGE = "인자로 받은 메시지"

/** `git format-patch` 가 앞머리에 붙이는 메일 헤더. 일반 diff 에는 없다. */
private fun ByteArray.withMailHeaders(subject: String, body: String = ""): ByteArray =
    (
        "From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n" +
            "From: $PATCH_AUTHOR_NAME <$PATCH_AUTHOR_EMAIL>\n" +
            "Date: Mon, 6 Sep 2026 00:00:00 +0900\n" +
            "Subject: [PATCH] $subject\n" +
            "\n" +
            (if (body.isEmpty()) "" else "$body\n\n") +
            "---\n"
        ).toByteArray() + this

class PatchApplySpec : FunSpec({

    test("빈 패치는 변경 없음으로 처리한다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")

            git.withPatchGateway { gateway ->
                gateway.apply(ByteArray(0), ApplyMode.WorkingTreeOnly) shouldBe ApplyOutcome.NoChange
                gateway.dryRun(ByteArray(0), ApplyMode.WorkingTreeOnly) shouldBe ApplyOutcome.NoChange
            }
        }
    }

    test("dry-run 은 성공해도 워킹트리와 인덱스를 바꾸지 않고, 실제 적용과 같은 결과를 돌려준다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nDRY\nl4\nl5\n") },
            )

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                val dryRun = gateway.dryRun(patch, ApplyMode.Index)

                dryRun shouldBe ApplyOutcome.Applied(listOf(FILE_PATH))
                git.snapshot() shouldBe before

                gateway.apply(patch, ApplyMode.Index) shouldBe dryRun
                git.readFile(FILE_PATH) shouldBe "l1\nl2\nDRY\nl4\nl5\n"
            }
        }
    }

    test("워킹트리 전용 모드는 인덱스를 건드리지 않는다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nWT\nl4\nl5\n") },
            )

            git.withPatchGateway { gateway ->
                val indexBefore = git.snapshot().indexEntries

                gateway.apply(patch, ApplyMode.WorkingTreeOnly)

                git.readFile(FILE_PATH) shouldBe "l1\nl2\nWT\nl4\nl5\n"
                git.snapshot().indexEntries shouldBe indexBefore
                git.status().call().modified shouldContainExactly setOf(FILE_PATH)
            }
        }
    }

    test("인덱스 모드는 워킹트리와 인덱스를 함께 올린다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nIDX\nl4\nl5\n") },
            )

            git.withPatchGateway { gateway ->
                gateway.apply(patch, ApplyMode.Index)

                git.readFile(FILE_PATH) shouldBe "l1\nl2\nIDX\nl4\nl5\n"
                val status = git.status().call()
                status.changed shouldContainExactly setOf(FILE_PATH)
                status.modified.isEmpty() shouldBe true
            }
        }
    }

    test("커밋 생성 모드는 패치 메타데이터의 작성자·메시지를 인자보다 우선한다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            val base = git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nCOMMIT\nl4\nl5\n") },
            ).withMailHeaders(subject = "패치가 담은 제목", body = "패치가 담은 본문")

            git.withPatchGateway { gateway ->
                gateway.apply(
                    patch,
                    ApplyMode.CreateCommit(Person("무시될 사람", "ignored@undine.dev"), FALLBACK_MESSAGE),
                )

                val head = git.headCommit()
                head.name shouldNotContain base.name
                head.authorIdent.name shouldBe PATCH_AUTHOR_NAME
                head.authorIdent.emailAddress shouldBe PATCH_AUTHOR_EMAIL
                head.fullMessage shouldContain "패치가 담은 제목"
                head.fullMessage shouldContain "패치가 담은 본문"
                git.readFile(FILE_PATH) shouldBe "l1\nl2\nCOMMIT\nl4\nl5\n"
            }
        }
    }

    test("메타데이터 없는 일반 diff 는 인자로 받은 작성자·메시지를 쓴다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nPLAIN\nl4\nl5\n") },
            )

            git.withPatchGateway { gateway ->
                gateway.apply(
                    patch,
                    ApplyMode.CreateCommit(Person("인자 작성자", "arg@undine.dev"), FALLBACK_MESSAGE),
                )

                val head = git.headCommit()
                head.authorIdent.name shouldBe "인자 작성자"
                head.fullMessage.trim() shouldBe FALLBACK_MESSAGE
            }
        }
    }

    test("작성자와 메시지가 패치에도 인자에도 없으면 적용 전에 거부한다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nNOPE\nl4\nl5\n") },
            )

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                shouldThrow<UndineException.StateViolation> {
                    gateway.apply(patch, ApplyMode.CreateCommit(author = null, message = null))
                }

                git.snapshot() shouldBe before
            }
        }
    }

    test("조상 blob 이 없는 패치가 붙지 않으면 충돌로 보고하고 워킹트리를 건드리지 않는다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            // 다른 저장소의 전혀 다른 내용에서 만든 패치 — 조상 blob 이 이 저장소에 없다.
            val foreign = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, "p\nq\nr\ns\nt\n") },
                change = { scratch -> scratch.writeFile(FILE_PATH, "p\nZ\nr\ns\nt\n") },
            )

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                gateway.apply(foreign, ApplyMode.WorkingTreeOnly) shouldBe
                    ApplyOutcome.Conflicted(listOf(FILE_PATH))

                git.snapshot() shouldBe before
                git.readFile(FILE_PATH) shouldNotContain CONFLICT_MARKER
            }
        }
    }

    test("조상 blob 이 있는 컨텍스트 불일치는 3-way 미지원으로 보고하고 워킹트리를 건드리지 않는다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            // 같은 내용에서 만든 패치라 조상 blob 이 이 저장소에도 있다.
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nTHREE\nl4\nl5\n") },
            )
            // 컨텍스트를 어긋나게 만든다 — 조상 blob 은 여전히 저장소에 남아 있다.
            git.writeFile(FILE_PATH, "l1\nl2\nl3\nl4\nMOVED\n")
            git.commitAll("moved")

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                gateway.apply(patch, ApplyMode.WorkingTreeOnly) shouldBe
                    ApplyOutcome.Unsupported(UnsupportedReason.THREE_WAY_REQUIRED)

                git.snapshot() shouldBe before
                git.readFile(FILE_PATH) shouldNotContain CONFLICT_MARKER
            }
        }
    }

    test("dry-run 도 붙지 않으면 같은 결과를 내고 아무것도 바꾸지 않는다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val foreign = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, "p\nq\nr\ns\nt\n") },
                change = { scratch -> scratch.writeFile(FILE_PATH, "p\nZ\nr\ns\nt\n") },
            )

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                val dryRun = gateway.dryRun(foreign, ApplyMode.WorkingTreeOnly)

                dryRun shouldBe ApplyOutcome.Conflicted(listOf(FILE_PATH))
                dryRun shouldBe gateway.apply(foreign, ApplyMode.WorkingTreeOnly)
                git.snapshot() shouldBe before
            }
        }
    }

    test("패치가 파일을 새로 만들면 그대로 만들어진다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile("nested/new.txt", "created\n") },
            )

            git.withPatchGateway { gateway ->
                gateway.apply(patch, ApplyMode.WorkingTreeOnly) shouldBe
                    ApplyOutcome.Applied(listOf("nested/new.txt"))

                git.readFile("nested/new.txt") shouldBe "created\n"
            }
        }
    }

    test("적용이 대상 blob 을 읽지 못하면 충돌이 아니라 Git 연산 실패로 올린다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nBROKEN\nl4\nl5\n") },
            )
            // 적용 기준이 되는 내용을 읽을 수 없게 만든다 — 붙지 않는 패치와는 다른 사실이다.
            git.corruptLooseObject(git.blobIdOf(FILE_PATH))

            git.withPatchGateway { gateway ->
                shouldThrow<UndineException.GitOperationFailed> {
                    gateway.apply(patch, ApplyMode.WorkingTreeOnly)
                }
                shouldThrow<UndineException.GitOperationFailed> {
                    gateway.dryRun(patch, ApplyMode.WorkingTreeOnly)
                }
            }
        }
    }
})
