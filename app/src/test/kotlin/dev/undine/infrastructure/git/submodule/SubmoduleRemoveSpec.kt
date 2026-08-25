package dev.undine.infrastructure.git.submodule

import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.util.FileUtils

class SubmoduleRemoveSpec : FunSpec({

    test("깨끗한 서브모듈은 확인 후 제거되고 네 곳 어디에도 잔재가 남지 않는다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)

            fixture.modulesText() shouldNotContain SUBMODULE_PATH
            fixture.configSubsections().shouldBeEmpty()
            fixture.indexPaths() shouldNotContain SUBMODULE_PATH
            fixture.file(SUBMODULE_PATH).exists() shouldBe false
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe false
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("열거되지 않은 형제 경로는 제거 대상이 아니다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val sibling = File(fixture.file(SIBLING_DIRECTORY).also(File::mkdirs), CHILD_FILE)
            sibling.writeText("접두사만 겹치는 형제\n")

            fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)

            sibling.exists() shouldBe true
            fixture.file(PARENT_FILE).exists() shouldBe true
        }
    }

    test("확인되지 않은 제거는 깨끗한 서브모듈에도 수행하지 않는다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = false)
            }

            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("커밋되지 않은 변경이 있으면 확인 여부와 무관하게 거부하고 무엇 때문인지 알린다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").writeText("아직 커밋하지 않은 작업\n")

            listOf(true, false).forEach { confirmed ->
                val failure = shouldThrow<UndineException.StateViolation> {
                    fixture.gateway.remove(SUBMODULE_PATH, confirmed)
                }
                failure.detail shouldContain CHILD_FILE
            }
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("추적되지 않은 파일이 있으면 제거를 거부한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            fixture.file("$SUBMODULE_PATH/$UNTRACKED_FILE").writeText("아직 커밋하지 않은 메모\n")

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain UNTRACKED_FILE
            fixture.file("$SUBMODULE_PATH/$UNTRACKED_FILE").exists() shouldBe true
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("무시된 파일만 있어도 제거를 거부한다 — Status.isClean 을 통과시키지 않는다") {
        openParent(repositoryWithIgnoringSubmodule()).use { fixture ->
            fixture.file("$SUBMODULE_PATH/$IGNORED_FILE").writeText("사용자가 남긴 로그\n")
            Git.open(fixture.file(SUBMODULE_PATH)).use { git -> git.status().call().isClean shouldBe true }

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain IGNORED_FILE
            fixture.file("$SUBMODULE_PATH/$IGNORED_FILE").exists() shouldBe true
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("중첩 서브모듈에 커밋되지 않은 변경이 있으면 최상위 제거를 거부한다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)
            val nested = fixture.file("$SUBMODULE_PATH/$NESTED_PATH/$NESTED_FILE")
            nested.writeText("중첩 서브모듈에서 진행한 작업\n")

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain NESTED_PATH
            nested.exists() shouldBe true
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("파일이 깨끗해도 부모 기록과 다른 HEAD 면 제거를 거부하고 .git/modules 를 남긴다") {
        val parent = repositoryWithSubmodule()
        divergeSubmodule(parent, SUBMODULE_PATH, CHILD_FILE)
        openParent(parent).use { fixture ->
            fixture.shouldHaveCleanSubmoduleWorkTree()

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain DIVERGED_REASON
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("기록 커밋에서 도달할 수 없는 로컬 브랜치만 있어도 제거를 거부한다") {
        val parent = repositoryWithSubmodule()
        branchSubmoduleWork(parent, SUBMODULE_PATH)
        openParent(parent).use { fixture ->
            fixture.shouldHaveCleanSubmoduleWorkTree()

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain LOCAL_BRANCH
            failure.detail shouldContain LOCAL_COMMIT_REASON
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("기록 커밋에서 도달할 수 없는 경량·주석 태그가 있으면 각각 제거를 거부한다") {
        listOf(LIGHTWEIGHT_TAG to false, ANNOTATED_TAG to true).forEach { (tag, annotated) ->
            val parent = repositoryWithSubmodule()
            tagSubmoduleWork(parent, SUBMODULE_PATH, tag, annotated)
            openParent(parent).use { fixture ->
                fixture.shouldHaveCleanSubmoduleWorkTree()

                val failure = shouldThrow<UndineException.StateViolation> {
                    fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
                }

                failure.detail shouldContain tag
                failure.detail shouldContain LOCAL_COMMIT_REASON
                fixture.shouldKeepSubmoduleIntact()
            }
        }
    }

    test("기록 커밋을 가리키는 주석 태그는 제거를 막지 않는다") {
        val parent = repositoryWithSubmodule()
        tagRecordedSubmoduleCommit(parent, SUBMODULE_PATH, RECORDED_TAG, annotated = true)
        openParent(parent).use { fixture ->
            fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)

            fixture.file(SUBMODULE_PATH).exists() shouldBe false
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe false
        }
    }

    test("사용자 정의 ref namespace의 도달 불가 커밋도 제거를 거부한다") {
        val parent = repositoryWithSubmodule()
        createCustomRefSubmoduleWork(parent, SUBMODULE_PATH)
        openParent(parent).use { fixture ->
            fixture.shouldHaveCleanSubmoduleWorkTree()

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain CUSTOM_LOCAL_REF
            failure.detail shouldContain LOCAL_COMMIT_REASON
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("stash 엔트리만 있어도 제거를 거부한다") {
        val parent = repositoryWithSubmodule()
        stashSubmoduleWork(parent, SUBMODULE_PATH)
        openParent(parent).use { fixture ->
            fixture.shouldHaveCleanSubmoduleWorkTree()

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain STASHED_REASON
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("중첩 서브모듈이 자기 부모 기록과 다른 HEAD 면 최상위 제거를 거부한다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)
            divergeSubmodule(fixture.work, "$SUBMODULE_PATH/$NESTED_PATH", NESTED_FILE)

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain NESTED_PATH
            failure.detail shouldContain DIVERGED_REASON
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("중첩 서브모듈의 도달 불가 태그가 있으면 최상위 제거를 거부한다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)
            tagSubmoduleWork(
                fixture.work,
                "$SUBMODULE_PATH/$NESTED_PATH",
                LIGHTWEIGHT_TAG,
                annotated = false,
            )

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain NESTED_PATH
            failure.detail shouldContain LIGHTWEIGHT_TAG
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("중첩까지 기록 커밋 그대로인 서브모듈은 확인 후 제거된다 — 기준점은 층마다 자기 부모다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)

            fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)

            fixture.file(SUBMODULE_PATH).exists() shouldBe false
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("유효한 저장소로 열리지 않는 경로는 판정 불가로 거부한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            deleteRecursively(fixture.file("$SUBMODULE_PATH/${Constants.DOT_GIT}"))

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain UNDECIDABLE_REASON
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
        }
    }

    test("deinit 뒤 워킹트리가 없어도 .git/modules의 다른 HEAD를 스캔해 제거를 거부한다") {
        val parent = repositoryWithSubmodule()
        divergeSubmodule(parent, SUBMODULE_PATH, CHILD_FILE)
        openParent(parent).use { fixture ->
            deleteRecursively(fixture.file(SUBMODULE_PATH))

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain DIVERGED_REASON
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe true
            fixture.configSubsections() shouldContain SUBMODULE_PATH
            fixture.indexPaths() shouldContain SUBMODULE_PATH
        }
    }

    test("deinit 된 중첩 서브모듈의 .git/modules 이력도 최상위 제거를 막는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)
            divergeSubmodule(fixture.work, "$SUBMODULE_PATH/$NESTED_PATH", NESTED_FILE)
            deleteRecursively(fixture.file(SUBMODULE_PATH))

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain NESTED_PATH
            failure.detail shouldContain DIVERGED_REASON
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe true
        }
    }

    test("다른 subsection 이름을 써도 deinit 뒤 경로 기반 .git/modules 이력을 스캔한다") {
        val parent = repositoryWithSubmodule()
        divergeSubmodule(parent, SUBMODULE_PATH, CHILD_FILE)
        openParent(parent).use { fixture ->
            fixture.renameModulesSection("다른-서브섹션")
            deleteRecursively(fixture.file(SUBMODULE_PATH))

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain DIVERGED_REASON
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe true
        }
    }

    test(".gitmodules 이름이 상위 경로·절대 경로로 기준을 벗어나면 거부하고 저장소 밖 파일을 지킨다") {
        val (work, sentinel) = submoduleBesideSentinel()
        openParent(work).use { fixture ->
            listOf("../../../$OUTSIDE_DIRECTORY", sentinel.parentFile.absolutePath).forEach { name ->
                fixture.renameModulesSection(name)

                val failure = shouldThrow<UndineException.StateViolation> {
                    fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
                }

                failure.detail shouldContain ESCAPE_REASON
                sentinel.readText() shouldBe SENTINEL_CONTENT
                fixture.file(SUBMODULE_PATH).exists() shouldBe true
            }
        }
    }

    test(".gitmodules 이름이 심볼릭 링크로 기준을 벗어나면 거부하고 저장소 밖 파일을 지킨다") {
        val (work, sentinel) = submoduleBesideSentinel()
        openParent(work).use { fixture ->
            val link = File(fixture.moduleGitDirectory(SUBMODULE_PATH).parentFile, ESCAPING_LINK)
            Files.createSymbolicLink(link.toPath(), sentinel.parentFile.toPath())
            fixture.renameModulesSection(ESCAPING_LINK)

            val failure = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            failure.detail shouldContain ESCAPE_REASON
            sentinel.readText() shouldBe SENTINEL_CONTENT
            fixture.file(SUBMODULE_PATH).exists() shouldBe true
        }
    }

    test("설정 저장이 실패하면 제거가 파일 정리로 넘어가지 않고 호출 전 상태가 남는다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val modulesBefore = fixture.modulesText()
            val gitlinkBefore = fixture.gitlinkId(SUBMODULE_PATH)

            fixture.withUnwritableConfig {
                shouldThrow<UndineException.GitOperationFailed> {
                    fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
                }
            }

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            fixture.modulesText() shouldBe modulesBefore
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe gitlinkBefore
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test(".gitmodules 정리가 실패하면 설정·인덱스가 되돌아오고 파일 삭제가 진행되지 않는다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val modulesBefore = fixture.modulesText()
            val gitlinkBefore = fixture.gitlinkId(SUBMODULE_PATH)

            withoutWritePermission(fixture.work) {
                shouldThrow<UndineException.GitOperationFailed> {
                    fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
                }
            }

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            fixture.modulesText() shouldBe modulesBefore
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe gitlinkBefore
            fixture.shouldKeepSubmoduleIntact()
        }
    }

    test("다른 선언이 남은 .gitmodules 저장 실패도 설정·인덱스·파일을 호출 전 상태로 되돌린다") {
        openParent(repositoryWithTwoSubmodules()).use { fixture ->
            val modulesBefore = fixture.modulesText()
            val gitlinkBefore = fixture.gitlinkId(SUBMODULE_PATH)

            fixture.withUnwritableModulesFile {
                shouldThrow<UndineException.GitOperationFailed> {
                    fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
                }
            }

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            fixture.configValue(SECOND_SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            fixture.modulesText() shouldBe modulesBefore
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe gitlinkBefore
            fixture.shouldKeepSubmoduleIntact()
            fixture.file(SECOND_SUBMODULE_PATH).exists() shouldBe true
        }
    }

    test(".gitmodules 스테이징 실패도 설정·파일·인덱스를 호출 전 상태로 되돌린다") {
        val (fixture, repository) = openParentWithLockFailure(repositoryWithTwoSubmodules())
        fixture.use {
            val modulesBefore = fixture.modulesText()
            val gitlinkBefore = fixture.gitlinkId(SUBMODULE_PATH)

            repository.failOnLockAttempt(attempt = 1)
            shouldThrow<UndineException.GitOperationFailed> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            fixture.configValue(SECOND_SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            fixture.modulesText() shouldBe modulesBefore
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe gitlinkBefore
            fixture.shouldKeepSubmoduleIntact()
            fixture.file(SECOND_SUBMODULE_PATH).exists() shouldBe true
        }
    }

    test("대상 gitlink 인덱스 갱신 실패도 설정·파일·인덱스를 호출 전 상태로 되돌린다") {
        val (fixture, repository) = openParentWithLockFailure(repositoryWithTwoSubmodules())
        fixture.use {
            val modulesBefore = fixture.modulesText()
            val gitlinkBefore = fixture.gitlinkId(SUBMODULE_PATH)

            repository.failOnLockAttempt(attempt = 2)
            shouldThrow<UndineException.GitOperationFailed> {
                fixture.gateway.remove(SUBMODULE_PATH, confirmed = true)
            }

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            fixture.configValue(SECOND_SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            fixture.modulesText() shouldBe modulesBefore
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe gitlinkBefore
            fixture.shouldKeepSubmoduleIntact()
            fixture.file(SECOND_SUBMODULE_PATH).exists() shouldBe true
        }
    }

    test("워킹트리와 .git/modules 삭제 실패 뒤에도 메타데이터는 호출 전 상태로 복원된다") {
        listOf(
            "워킹트리" to { fixture: ParentFixture -> fixture.file(SUBMODULE_PATH) },
            ".git/modules" to { fixture: ParentFixture -> fixture.moduleGitDirectory(SUBMODULE_PATH) },
        ).forEach { (_, targetOf) ->
            openParent(repositoryWithSubmodule()).use { fixture ->
                val modulesBefore = fixture.modulesText()
                val gitlinkBefore = fixture.gitlinkId(SUBMODULE_PATH)
                val failedDirectory = targetOf(fixture).canonicalFile
                val failureTarget = failedDirectory.toPath()

                shouldThrow<IOException> {
                    fixture.repository.removeSubmodule(SUBMODULE_PATH, confirmed = true) { entry ->
                        if (entry.canonicalFile.toPath().startsWith(failureTarget)) {
                            throw IOException("주입한 삭제 실패")
                        }
                        FileUtils.delete(entry, FileUtils.SKIP_MISSING or FileUtils.RETRY)
                    }
                }

                fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
                fixture.modulesText() shouldBe modulesBefore
                fixture.gitlinkId(SUBMODULE_PATH) shouldBe gitlinkBefore
                failedDirectory.exists() shouldBe true
            }
        }
    }

    test("없는 서브모듈 제거는 NotFound.SUBMODULE 로 보고한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val failure = shouldThrow<UndineException.NotFound> {
                fixture.gateway.remove(MISSING_PATH, confirmed = true)
            }

            failure.kind shouldBe UndineException.NotFound.Kind.SUBMODULE
            fixture.shouldKeepSubmoduleIntact()
        }
    }
})
