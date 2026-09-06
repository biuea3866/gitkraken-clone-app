package dev.undine.infrastructure.git.patch

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.CommitRange
import dev.undine.domain.patch.WorkingTreeScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

private const val OTHER_FILE_PATH = "b.txt"
private const val GITIGNORE_PATH = ".gitignore"
private const val IGNORED_FILE_PATH = "ignored.txt"
private const val MISSING_COMMIT = "0123456789012345678901234567890123456789"

class PatchExportSpec : FunSpec({

    test("커밋 하나를 patch 로 내보내고 다시 적용하면 동일한 변경이 재현된다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            val base = git.commitAll("base")
            git.writeFile(FILE_PATH, "l1\nl2\nCHANGED\nl4\nl5\n")
            val changed = git.commitAll("changed")

            git.withPatchGateway { gateway ->
                val export = gateway.export(CommitRange(CommitId.of(base.name), CommitId.of(changed.name)))
                git.resetHardTo(base)

                gateway.apply(export.combined, ApplyMode.WorkingTreeOnly) shouldBe
                    ApplyOutcome.Applied(listOf(FILE_PATH))
                git.readFile(FILE_PATH) shouldBe "l1\nl2\nCHANGED\nl4\nl5\n"
            }
        }
    }

    test("여러 커밋은 커밋별 패치와 통합 패치를 한 번에 돌려준다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, "one\n")
            val base = git.commitAll("base")
            git.writeFile(FILE_PATH, "one\ntwo\n")
            val second = git.commitAll("second")
            git.writeFile(FILE_PATH, "one\ntwo\nthree\n")
            val third = git.commitAll("third")

            git.withPatchGateway { gateway ->
                val export = gateway.export(CommitRange(CommitId.of(base.name), CommitId.of(third.name)))

                export.perCommit.map { patch -> patch.commit } shouldContainExactly listOf(
                    CommitId.of(second.name),
                    CommitId.of(third.name),
                )
                export.perCommit.forEach { patch -> patch.bytes.decodeToString() shouldContain "diff --git" }

                git.resetHardTo(base)
                gateway.apply(export.combined, ApplyMode.WorkingTreeOnly)
                git.readFile(FILE_PATH) shouldBe "one\ntwo\nthree\n"
            }
        }
    }

    test("범위의 from 이 null 이면 루트 커밋부터 내보낸다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, "root\n")
            val root = git.commitAll("root")

            git.withPatchGateway { gateway ->
                val export = gateway.export(CommitRange(from = null, to = CommitId.of(root.name)))

                export.perCommit.map { patch -> patch.commit } shouldContainExactly listOf(CommitId.of(root.name))
                export.combined.decodeToString() shouldContain "new file mode"
            }
        }
    }

    test("스테이징한 변경만 내보내고, 되돌린 뒤 적용하면 내용·인덱스가 재현된다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            val base = git.commitAll("base")
            git.writeFile(FILE_PATH, "staged\n")
            git.stageAll()
            git.writeFile(OTHER_FILE_PATH, "unstaged only\n")

            git.withPatchGateway { gateway ->
                val export = gateway.export(WorkingTreeScope.STAGED)
                export.perCommit.shouldBeEmpty()

                // 되돌려도 추적되지 않은 b.txt 는 남는다 — 범위 밖 변경이 그대로 있는 상태에 적용한다.
                git.resetHardTo(base)

                gateway.apply(export.combined, ApplyMode.Index) shouldBe ApplyOutcome.Applied(listOf(FILE_PATH))
                git.readFile(FILE_PATH) shouldBe "staged\n"

                val status = git.status().call()
                status.changed shouldContainExactly setOf(FILE_PATH)
                status.modified.isEmpty() shouldBe true
                // 스테이징하지 않은 변경은 패치에 실리지 않았다 — 내용도 인덱스도 그대로다.
                status.untracked shouldContainExactly setOf(OTHER_FILE_PATH)
                git.readFile(OTHER_FILE_PATH) shouldBe "unstaged only\n"
            }
        }
    }

    test("스테이징하지 않은 변경만 내보내고, 되돌린 뒤 적용하면 재현된다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            val base = git.commitAll("base")
            git.writeFile(FILE_PATH, "l1\nl2\nUNSTAGED\nl4\nl5\n")

            git.withPatchGateway { gateway ->
                val export = gateway.export(WorkingTreeScope.UNSTAGED)
                git.resetHardTo(base)

                gateway.apply(export.combined, ApplyMode.WorkingTreeOnly)
                git.readFile(FILE_PATH) shouldBe "l1\nl2\nUNSTAGED\nl4\nl5\n"
            }
        }
    }

    test("ALL 은 스테이징 여부와 무관하게 전체 변경을 담고, 되돌린 뒤 적용하면 재현된다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.writeFile(GITIGNORE_PATH, "$IGNORED_FILE_PATH\n")
            val base = git.commitAll("base")
            git.writeFile(FILE_PATH, "l1\nl2\nSTAGED\nl4\nl5\n")
            git.stageAll()
            git.writeFile(OTHER_FILE_PATH, "brand new\n")
            git.writeFile(IGNORED_FILE_PATH, "ignored\n")

            git.withPatchGateway { gateway ->
                val export = gateway.export(WorkingTreeScope.ALL)

                // 추적되지 않은 두 파일은 되돌리기로 사라지지 않으므로 직접 지워 깨끗한 적용 대상을 만든다.
                git.resetHardTo(base)
                File(git.repository.workTree, OTHER_FILE_PATH).delete()
                File(git.repository.workTree, IGNORED_FILE_PATH).delete()

                gateway.apply(export.combined, ApplyMode.Index) shouldBe
                    ApplyOutcome.Applied(listOf(FILE_PATH, OTHER_FILE_PATH))
                git.readFile(FILE_PATH) shouldBe "l1\nl2\nSTAGED\nl4\nl5\n"
                git.readFile(OTHER_FILE_PATH) shouldBe "brand new\n"

                val status = git.status().call()
                status.changed shouldContainExactly setOf(FILE_PATH)
                status.added shouldContainExactly setOf(OTHER_FILE_PATH)
                status.modified.isEmpty() shouldBe true
                status.untracked.isEmpty() shouldBe true
                // 무시된 파일은 범위 밖이라 패치에 실리지 않았다.
                git.exists(IGNORED_FILE_PATH) shouldBe false
            }
        }
    }

    test("없는 커밋을 내보내려 하면 NotFound 다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")

            git.withPatchGateway { gateway ->
                val failure = shouldThrow<UndineException.NotFound> {
                    gateway.export(CommitRange(from = null, to = CommitId.of(MISSING_COMMIT)))
                }

                failure.kind shouldBe UndineException.NotFound.Kind.COMMIT
            }
        }
    }

    test("범위 내보내기가 커밋 객체를 읽지 못하면 NotFound 가 아니라 Git 연산 실패로 올린다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            val head = git.commitAll("base")
            // 객체는 제자리에 있는데 내용이 깨졌다 — "그런 커밋 없음" 과는 다른 사실이다.
            git.corruptLooseObject(head)

            git.withPatchGateway { gateway ->
                shouldThrow<UndineException.GitOperationFailed> {
                    gateway.export(CommitRange(from = null, to = CommitId.of(head.name)))
                }
            }
        }
    }
})
