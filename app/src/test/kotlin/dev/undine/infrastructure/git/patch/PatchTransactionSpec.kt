package dev.undine.infrastructure.git.patch

import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

private const val SCRIPT_PATH = "x.sh"
private const val SCRIPT_BASE = "#!/bin/sh\necho base\n"
private const val SCRIPT_CHANGED = "#!/bin/sh\necho changed\n"
private const val SWITCH_PATH = "a"
private const val NESTED_PATH = "a/b"
private const val OUTSIDE_KEEP = "keep.txt"
private const val OUTSIDE_NESTED_DIR = "b"
private const val EMPTY_DIR_PATH = "empty"

/** 경로 하나만 건드리는 새 파일 패치 — 경로 안전 판정만 보기 위한 최소 형태다. */
private fun addFilePatch(path: String): ByteArray =
    (
        "diff --git a/$path b/$path\n" +
            "new file mode 100644\n" +
            "--- /dev/null\n" +
            "+++ b/$path\n" +
            "@@ -0,0 +1 @@\n" +
            "+injected\n"
        ).toByteArray()

class PatchTransactionSpec : FunSpec({

    test("mode 변경 패치를 적용하면 실행 권한이 올라간다") {
        initRepository().use { git ->
            git.writeFile(SCRIPT_PATH, SCRIPT_BASE)
            git.commitAll("base")
            val patch = modeChangePatch()

            git.withPatchGateway { gateway ->
                git.isExecutable(SCRIPT_PATH) shouldBe false

                gateway.apply(patch, ApplyMode.WorkingTreeOnly)

                git.isExecutable(SCRIPT_PATH) shouldBe true
                git.readFile(SCRIPT_PATH) shouldBe SCRIPT_CHANGED
            }
        }
    }

    test("mode 변경 패치가 승격 후 검증에 실패하면 실행 권한이 적용 전 값으로 돌아온다") {
        initRepository().use { git ->
            git.writeFile(SCRIPT_PATH, SCRIPT_BASE)
            git.commitAll("base")
            val patch = modeChangePatch()

            git.withPatchGateway(alwaysFailingPromotion) { gateway ->
                val before = git.snapshot()

                shouldThrow<UndineException.GitOperationFailed> {
                    gateway.apply(patch, ApplyMode.Index)
                }

                git.snapshot() shouldBe before
                git.isExecutable(SCRIPT_PATH) shouldBe false
                git.readFile(SCRIPT_PATH) shouldBe SCRIPT_BASE
            }
        }
    }

    test("파일을 디렉터리로 바꾸는 패치를 적용하면 a 가 디렉터리가 된다") {
        initRepository().use { git ->
            git.writeFile(SWITCH_PATH, "original\n")
            git.commitAll("base")

            git.withPatchGateway { gateway ->
                gateway.apply(fileToDirectoryPatch(), ApplyMode.WorkingTreeOnly)

                git.readFile(NESTED_PATH) shouldBe "nested\n"
                File(git.repository.workTree, SWITCH_PATH).isDirectory shouldBe true
            }
        }
    }

    test("a 삭제와 a/b 추가 패치가 실패하면 원본 파일 a 가 그대로 복구된다") {
        initRepository().use { git ->
            git.writeFile(SWITCH_PATH, "original\n")
            git.commitAll("base")

            git.withPatchGateway(alwaysFailingPromotion) { gateway ->
                val before = git.snapshot()

                shouldThrow<UndineException.GitOperationFailed> {
                    gateway.apply(fileToDirectoryPatch(), ApplyMode.Index)
                }

                git.snapshot() shouldBe before
                git.readFile(SWITCH_PATH) shouldBe "original\n"
                File(git.repository.workTree, SWITCH_PATH).isFile shouldBe true
                git.exists(NESTED_PATH) shouldBe false
            }
        }
    }

    test("패치가 새 심볼릭 링크를 만들면 링크로 만들어진다") {
        val outside = tempdir()

        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val patch = patchBetween(
                seed = { scratch -> scratch.writeFile(FILE_PATH, FIVE_LINES) },
                change = { scratch ->
                    Files.createSymbolicLink(File(scratch.repository.workTree, "linked").toPath(), outside.toPath())
                },
            )

            git.withPatchGateway { gateway ->
                gateway.apply(patch, ApplyMode.WorkingTreeOnly) shouldBe ApplyOutcome.Applied(listOf("linked"))

                git.isSymbolicLink("linked") shouldBe true
            }
        }
    }

    // JGit `PatchApplier` 는 디렉터리를 같은 이름의 파일·링크로 바꾸는 패치를 적용하지 못한다.
    // 중요한 것은 **그때 워킹트리에 아무것도 남지 않는다**는 것이다 — 절반만 적용된 상태로 실패하면
    // 사용자가 수습할 방법이 없다.
    test("디렉터리를 심볼릭 링크로 바꾸는 패치는 붙지 않고 워킹트리를 건드리지 않는다") {
        val outside = tempdir()
        File(outside, OUTSIDE_KEEP).writeText("바깥 파일은 살아 있어야 한다\n")

        initRepository().use { git ->
            git.writeFile(NESTED_PATH, "nested\n")
            git.commitAll("base")
            val patch = directoryToSymlinkPatch(outside)

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                gateway.apply(patch, ApplyMode.Index) shouldBe ApplyOutcome.Conflicted(listOf(SWITCH_PATH))

                git.snapshot() shouldBe before
                git.isSymbolicLink(SWITCH_PATH) shouldBe false
                File(outside, OUTSIDE_KEEP).exists() shouldBe true
            }
        }
    }

    // UND-31 라운드 6 의 p0 을 그 자리에서 막는다: `a` 가 심볼릭 링크로 바뀐 뒤 복원이 링크를
    // 따라가면 **저장소 바깥**을 지운다.
    //
    // 게이트웨이가 아니라 트랜잭션을 직접 돌리는 이유: JGit 은 이 토폴로지 전환을 패치로 적용해 주지
    // 않으므로(바로 위 스펙), 승격이 뒤집어 놓은 상태를 워킹트리에 직접 만들어 복원만 재현한다.
    // 실제 저장소·실제 심볼릭 링크를 쓰므로 위험 자체는 그대로다.
    test("복원은 심볼릭 링크를 따라가지 않아 링크 바깥 대상을 지우지 않는다") {
        val outside = tempdir()
        File(outside, OUTSIDE_KEEP).writeText("바깥 파일은 살아 있어야 한다\n")

        initRepository().use { git ->
            git.writeFile(NESTED_PATH, "nested\n")
            git.commitAll("base")
            val repository = git.repository
            val transaction = repository.newObjectInserter().use { inserter ->
                repository.recordPatchTree(listOf(SWITCH_PATH, NESTED_PATH), inserter)
            }

            // 승격이 조상 토폴로지를 뒤집어 놓은 상태 — `a` 가 디렉터리에서 바깥을 가리키는 링크가 됐다.
            File(repository.workTree, SWITCH_PATH).deleteRecursively()
            Files.createSymbolicLink(File(repository.workTree, SWITCH_PATH).toPath(), outside.toPath())
            git.isSymbolicLink(SWITCH_PATH) shouldBe true

            transaction.restore()

            git.isSymbolicLink(SWITCH_PATH) shouldBe false
            git.readFile(NESTED_PATH) shouldBe "nested\n"
            File(outside, OUTSIDE_KEEP).exists() shouldBe true
            File(outside, OUTSIDE_KEEP).readText() shouldBe "바깥 파일은 살아 있어야 한다\n"
        }
    }

    // 위 스펙의 `outside` 는 최상위 파일 하나뿐이라 `a/b` 를 이어 붙여도 바깥에 그 자리가 없다.
    // 바깥에 **같은 모양의 하위 디렉터리**가 있으면 `NOFOLLOW` 만으로는 못 막는다 —
    // `NOFOLLOW` 는 경로의 마지막 요소에만 걸리므로 `root.resolve("a/b")` 는 이미 링크를 따라간다.
    test("복원은 링크 조상을 따라가지 않아 링크 바깥의 같은 이름 하위 트리를 지우지 않는다") {
        val outside = tempdir()
        val outsideNested = File(outside, OUTSIDE_NESTED_DIR).apply { mkdirs() }
        File(outsideNested, OUTSIDE_KEEP).writeText("바깥 하위 파일은 살아 있어야 한다\n")

        initRepository().use { git ->
            git.writeFile(NESTED_PATH, "nested\n")
            git.commitAll("base")
            val repository = git.repository
            val transaction = repository.newObjectInserter().use { inserter ->
                repository.recordPatchTree(listOf(SWITCH_PATH, NESTED_PATH), inserter)
            }

            // 승격이 `a` 를 바깥을 가리키는 링크로 바꿔 놓은 상태. 바깥에도 `b/` 가 있어서
            // `a/b` 를 그대로 이어 붙이면 저장소 밖 디렉터리에 닿는다.
            File(repository.workTree, SWITCH_PATH).deleteRecursively()
            Files.createSymbolicLink(File(repository.workTree, SWITCH_PATH).toPath(), outside.toPath())

            transaction.restore()

            File(outsideNested, OUTSIDE_KEEP).exists() shouldBe true
            File(outsideNested, OUTSIDE_KEEP).readText() shouldBe "바깥 하위 파일은 살아 있어야 한다\n"
            outsideNested.isDirectory shouldBe true
            git.isSymbolicLink(SWITCH_PATH) shouldBe false
            git.readFile(NESTED_PATH) shouldBe "nested\n"
        }
    }

    test("경로 이탈이 포함된 패치는 거부되고 아무것도 바뀌지 않는다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                shouldThrow<UndineException.StateViolation> {
                    gateway.apply(addFilePatch("../evil.txt"), ApplyMode.WorkingTreeOnly)
                }

                git.snapshot() shouldBe before
                File(git.repository.workTree.parentFile, "evil.txt").exists() shouldBe false
            }
        }
    }

    test("절대 경로 패치는 거부된다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")

            git.withPatchGateway { gateway ->
                shouldThrow<UndineException.StateViolation> {
                    gateway.apply(addFilePatch("/tmp/undine-evil.txt"), ApplyMode.WorkingTreeOnly)
                }
            }
        }
    }

    test("어느 깊이의 .git 변형도 거부된다") {
        listOf(".git/config", "sub/.git/hooks/pre-commit", "sub/.GIT/x", "sub/.git./x").forEach { evilPath ->
            initRepository().use { git ->
                git.writeFile(FILE_PATH, FIVE_LINES)
                git.commitAll("base")

                git.withPatchGateway { gateway ->
                    val before = git.snapshot()

                    shouldThrow<UndineException.StateViolation> {
                        gateway.apply(addFilePatch(evilPath), ApplyMode.WorkingTreeOnly)
                    }

                    git.snapshot() shouldBe before
                }
            }
        }
    }

    test("심볼릭 링크를 경유하는 패치는 거부되고 링크 바깥에 파일을 만들지 않는다") {
        val outside = tempdir()

        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            Files.createSymbolicLink(File(git.repository.workTree, "link").toPath(), outside.toPath())
            git.commitAll("base")

            git.withPatchGateway { gateway ->
                shouldThrow<UndineException.StateViolation> {
                    gateway.apply(addFilePatch("link/evil.txt"), ApplyMode.WorkingTreeOnly)
                }

                File(outside, "evil.txt").exists() shouldBe false
            }
        }
    }

    // 트리는 빈 디렉터리를 담지 못한다. 담지 못하는 자리를 적용해 놓고 승격이 실패하면 복원이
    // 그 디렉터리를 되살릴 수 없어 사용자 디렉터리가 사라진다 — 그래서 적용 전에 거부한다.
    test("적용 대상 자리가 빈 추적되지 않은 디렉터리면 거부하고 그 디렉터리를 남긴다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val emptyDirectory = File(git.repository.workTree, EMPTY_DIR_PATH).apply { mkdirs() }

            git.withPatchGateway { gateway ->
                shouldThrow<UndineException.StateViolation> {
                    gateway.apply(addFilePatch(EMPTY_DIR_PATH), ApplyMode.WorkingTreeOnly)
                }

                emptyDirectory.isDirectory shouldBe true
            }
        }
    }

    test("적용 대상의 조상이 빈 추적되지 않은 디렉터리면 거부하고 그 디렉터리를 남긴다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            val emptyDirectory = File(git.repository.workTree, EMPTY_DIR_PATH).apply { mkdirs() }

            git.withPatchGateway { gateway ->
                shouldThrow<UndineException.StateViolation> {
                    gateway.apply(addFilePatch("$EMPTY_DIR_PATH/injected.txt"), ApplyMode.WorkingTreeOnly)
                }

                emptyDirectory.isDirectory shouldBe true
                git.exists("$EMPTY_DIR_PATH/injected.txt") shouldBe false
            }
        }
    }

    // 대상 자리가 통째로 치워지는 전환에서는 그 **아래**의 빈 디렉터리도 함께 사라진다 —
    // 대상 자신은 추적되는 파일을 품고 있어 복원되는데, 빈 하위 디렉터리는 트리에 없어 돌아오지 않는다.
    test("적용 대상 아래에 빈 추적되지 않은 디렉터리가 있으면 거부한다") {
        val outside = tempdir()

        initRepository().use { git ->
            git.writeFile(NESTED_PATH, "nested\n")
            git.commitAll("base")
            val nestedEmpty = File(git.repository.workTree, "$SWITCH_PATH/$EMPTY_DIR_PATH").apply { mkdirs() }

            git.withPatchGateway { gateway ->
                val before = git.snapshot()

                shouldThrow<UndineException.StateViolation> {
                    gateway.apply(directoryToSymlinkPatch(outside), ApplyMode.WorkingTreeOnly)
                }

                git.snapshot() shouldBe before
                nestedEmpty.isDirectory shouldBe true
            }
        }
    }

    test("적용 대상에 추적되지 않은 파일이 있으면 거부하고 그 파일을 건드리지 않는다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("base")
            git.writeFile("new.txt", "사용자가 만든 추적되지 않은 파일\n")

            git.withPatchGateway { gateway ->
                shouldThrow<UndineException.StateViolation> {
                    gateway.apply(addFilePatch("new.txt"), ApplyMode.WorkingTreeOnly)
                }

                git.readFile("new.txt") shouldBe "사용자가 만든 추적되지 않은 파일\n"
            }
        }
    }
})

/** 내용과 실행 권한을 함께 바꾸는 패치 — 승격 실패 시 되돌려야 할 것이 mode 에도 있다. */
private fun FunSpec.modeChangePatch(): ByteArray =
    patchBetween(
        seed = { scratch -> scratch.writeFile(SCRIPT_PATH, SCRIPT_BASE) },
        change = { scratch ->
            scratch.writeFile(SCRIPT_PATH, SCRIPT_CHANGED).setExecutable(true)
        },
    )

/** `a` 를 지우고 `a/b` 를 만드는 패치 — 파일↔디렉터리 전환이 복원에서 가장 잘 깨지는 자리다. */
private fun FunSpec.fileToDirectoryPatch(): ByteArray =
    patchBetween(
        seed = { scratch -> scratch.writeFile(SWITCH_PATH, "original\n") },
        change = { scratch ->
            File(scratch.repository.workTree, SWITCH_PATH).delete()
            scratch.writeFile(NESTED_PATH, "nested\n")
        },
    )

/** `a/b` 를 지우고 `a` 를 바깥을 가리키는 심볼릭 링크로 바꾸는 패치 — 조상 토폴로지가 뒤집힌다. */
private fun FunSpec.directoryToSymlinkPatch(outside: File): ByteArray =
    patchBetween(
        seed = { scratch -> scratch.writeFile(NESTED_PATH, "nested\n") },
        change = { scratch ->
            File(scratch.repository.workTree, SWITCH_PATH).deleteRecursively()
            Files.createSymbolicLink(
                File(scratch.repository.workTree, SWITCH_PATH).toPath(),
                outside.toPath(),
            )
        },
    )
