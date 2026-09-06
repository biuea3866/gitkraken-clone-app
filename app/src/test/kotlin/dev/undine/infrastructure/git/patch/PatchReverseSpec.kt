package dev.undine.infrastructure.git.patch

import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.UnsupportedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val SOURCE_PATH = "src.txt"
private const val UNSUPPORTED = "역적용이 지원하지 않는 변경"

private val copyPatch = (
    "diff --git a/$SOURCE_PATH b/copy.txt\n" +
        "similarity index 100%\n" +
        "copy from $SOURCE_PATH\n" +
        "copy to copy.txt\n"
    ).toByteArray()

private val renamePatch = (
    "diff --git a/$SOURCE_PATH b/renamed.txt\n" +
        "similarity index 100%\n" +
        "rename from $SOURCE_PATH\n" +
        "rename to renamed.txt\n"
    ).toByteArray()

private val modeChangeOnlyPatch = (
    "diff --git a/$SOURCE_PATH b/$SOURCE_PATH\n" +
        "old mode 100644\n" +
        "new mode 100755\n"
    ).toByteArray()

private val binaryPatch = (
    "diff --git a/bin.dat b/bin.dat\n" +
        "index 1111111..2222222 100644\n" +
        "Binary files a/bin.dat and b/bin.dat differ\n"
    ).toByteArray()

class PatchReverseSpec : FunSpec({

    test("순수 hunk 패치는 역적용으로 원래 내용으로 돌아간다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(FILE_PATH, "l1\nl2\nREVERSED\nl4\nl5\n") },
            )

            git.withPatchGateway { gateway ->
                gateway.apply(patch, ApplyMode.WorkingTreeOnly)
                git.readFile(FILE_PATH) shouldBe "l1\nl2\nREVERSED\nl4\nl5\n"

                gateway.applyReversed(patch) shouldBe ApplyOutcome.Applied(listOf(FILE_PATH))

                git.readFile(FILE_PATH) shouldBe FIVE_LINES
            }
        }
    }

    test("마지막 줄에 개행이 없는 파일도 역적용으로 그대로 돌아간다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, "first\nlast without newline")
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, "first\nlast without newline") },
                change = { scratch -> scratch.writeFile(FILE_PATH, "first\nchanged without newline") },
            )

            git.withPatchGateway { gateway ->
                gateway.apply(patch, ApplyMode.WorkingTreeOnly)
                gateway.applyReversed(patch) shouldBe ApplyOutcome.Applied(listOf(FILE_PATH))

                git.readFile(FILE_PATH) shouldBe "first\nlast without newline"
            }
        }
    }

    test("파일을 추가하는 패치의 역적용은 그 파일을 지운다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile("added.txt", "added\n") },
            )

            git.withPatchGateway { gateway ->
                gateway.apply(patch, ApplyMode.Index)
                git.exists("added.txt") shouldBe true

                gateway.applyReversed(patch) shouldBe ApplyOutcome.Applied(listOf("added.txt"))

                git.exists("added.txt") shouldBe false
            }
        }
    }

    test("빈 패치의 역적용은 변경 없음이다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")

            git.withPatchGateway { gateway ->
                gateway.applyReversed(ByteArray(0)) shouldBe ApplyOutcome.NoChange
            }
        }
    }

    listOf(
        "copy" to copyPatch,
        "rename" to renamePatch,
        "mode 변경" to modeChangeOnlyPatch,
        "binary" to binaryPatch,
    ).forEach { (label, patch) ->
        test("$UNSUPPORTED — $label 이 들어 있으면 되돌리지 않는다") {
            initRepository().use { git ->
                git.writeFile(SOURCE_PATH, FIVE_LINES)
                git.commitAll("base")

                git.withPatchGateway { gateway ->
                    val before = git.snapshot()

                    gateway.applyReversed(patch) shouldBe
                        ApplyOutcome.Unsupported(UnsupportedReason.REVERSE_NOT_PURE_HUNK)

                    git.snapshot() shouldBe before
                }
            }
        }
    }

    // `all { }` 로 판정하면 섞인 패치가 순수 hunk 경로로 흘러 copy 의 source 를 덮어쓴다
    // (UND-31 라운드 4 의 p0). 섞였을 때 미지원이 나오는지가 그 회귀를 막는 자리다.
    test("$UNSUPPORTED — 순수 hunk 에 copy 가 하나라도 섞이면 되돌리지 않는다") {
        initRepository().use { git ->
            git.writeFile(SOURCE_PATH, FIVE_LINES)
            git.commitAll("base")
            val pureHunk = patchBetween(
                seed = { scratch -> scratch.writeFile(SOURCE_PATH, FIVE_LINES) },
                change = { scratch -> scratch.writeFile(SOURCE_PATH, "l1\nl2\nMIXED\nl4\nl5\n") },
            )

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                gateway.applyReversed(pureHunk + copyPatch) shouldBe
                    ApplyOutcome.Unsupported(UnsupportedReason.REVERSE_NOT_PURE_HUNK)

                git.snapshot() shouldBe before
                git.readFile(SOURCE_PATH) shouldBe FIVE_LINES
            }
        }
    }
})
