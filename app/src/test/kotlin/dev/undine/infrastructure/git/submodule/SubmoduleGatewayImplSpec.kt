package dev.undine.infrastructure.git.submodule

import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SubmoduleGatewayImplSpec : FunSpec({

    test("서브모듈이 없는 저장소는 빈 목록을 반환한다") {
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("서브모듈 목록이 경로·URL·상태와 함께 반환된다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val submodules = fixture.gateway.list()

            submodules shouldHaveSize 1
            val submodule = submodules.single()
            submodule.path shouldBe SUBMODULE_PATH
            submodule.url.shouldNotBeNull()
            submodule.state.initialized shouldBe true
            submodule.state.locallyModified shouldBe false
            submodule.state.divergedFromRecorded shouldBe false
        }
    }

    test("미초기화 서브모듈을 초기화하면 최신 상태가 된다") {
        openParent(repositoryWithUninitializedSubmodule()).use { fixture ->
            fixture.gateway.list().single().state.initialized shouldBe false

            fixture.gateway.initialize(SUBMODULE_PATH)

            val state = fixture.gateway.list().single().state
            state.initialized shouldBe true
            state.locallyModified shouldBe false
            state.divergedFromRecorded shouldBe false
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
        }
    }

    test("부모가 기록한 커밋과 실제 HEAD 가 다르면 어긋남으로 판정된다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            divergeSubmodule(fixture.work, SUBMODULE_PATH, CHILD_FILE)

            val state = fixture.gateway.list().single().state
            state.initialized shouldBe true
            state.divergedFromRecorded shouldBe true
            state.locallyModified shouldBe false
        }
    }

    test("서브모듈 안에 커밋되지 않은 변경이 있으면 수정됨으로 판정된다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").writeText("커밋하지 않은 변경\n")

            val state = fixture.gateway.list().single().state
            state.locallyModified shouldBe true
            state.divergedFromRecorded shouldBe false
        }
    }

    test("수정됨과 어긋남이 동시에 성립하면 둘 다 보고한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            divergeSubmodule(fixture.work, SUBMODULE_PATH, CHILD_FILE)
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").writeText("커밋하지 않은 변경\n")

            val state = fixture.gateway.list().single().state
            state.locallyModified shouldBe true
            state.divergedFromRecorded shouldBe true
        }
    }

    test("재귀가 꺼진 초기화는 중첩 서브모듈을 건드리지 않는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = false)

            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.file("$SUBMODULE_PATH/$NESTED_PATH/$NESTED_FILE").exists() shouldBe false
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.initialized shouldBe false
        }
    }

    test("재귀 초기화는 중첩 서브모듈까지 초기화한다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)

            fixture.file("$SUBMODULE_PATH/$NESTED_PATH/$NESTED_FILE").exists() shouldBe true
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.initialized shouldBe true
        }
    }

    test("업데이트는 부모가 기록한 커밋으로 서브모듈을 되돌린다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            divergeSubmodule(fixture.work, SUBMODULE_PATH, CHILD_FILE)

            fixture.gateway.update(SUBMODULE_PATH)

            fixture.gateway.list().single().state.divergedFromRecorded shouldBe false
        }
    }

    test("재귀가 꺼진 업데이트는 중첩 서브모듈에 적용하지 않는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)
            divergeSubmodule(fixture.file(SUBMODULE_PATH), NESTED_PATH, NESTED_FILE)

            fixture.gateway.update(SUBMODULE_PATH, recursive = false)
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.divergedFromRecorded shouldBe true

            fixture.gateway.update(SUBMODULE_PATH, recursive = true)
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.divergedFromRecorded shouldBe false
        }
    }

    test("recursive 를 생략한 초기화는 비재귀가 기본이라 중첩 서브모듈을 건드리지 않는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH)

            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.file("$SUBMODULE_PATH/$NESTED_PATH/$NESTED_FILE").exists() shouldBe false
            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.initialized shouldBe false
        }
    }

    test("recursive 를 생략한 업데이트는 비재귀가 기본이라 중첩 서브모듈에 적용하지 않는다") {
        openParent(repositoryWithNestedSubmodule()).use { fixture ->
            fixture.gateway.initialize(SUBMODULE_PATH, recursive = true)
            divergeSubmodule(fixture.file(SUBMODULE_PATH), NESTED_PATH, NESTED_FILE)

            fixture.gateway.update(SUBMODULE_PATH)

            submodulesOf(fixture.file(SUBMODULE_PATH)).single().state.divergedFromRecorded shouldBe true
        }
    }

    test("초기화되지 않은 서브모듈 업데이트는 상태 위반으로 거부한다") {
        openParent(repositoryWithUninitializedSubmodule()).use { fixture ->
            shouldThrow<UndineException.StateViolation> { fixture.gateway.update(SUBMODULE_PATH) }

            fixture.gateway.list().single().state.initialized shouldBe false
        }
    }

    test("추가는 .gitmodules 항목·워킹트리·브랜치 설정을 남긴다") {
        val origin = seedRepository(CHILD_FILE)
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            val added = fixture.gateway.add(origin.absolutePath, SUBMODULE_PATH, MAIN)

            added.path shouldBe SUBMODULE_PATH
            added.state.initialized shouldBe true
            fixture.modulesText() shouldContain SUBMODULE_PATH
            fixture.modulesText() shouldContain "branch = $MAIN"
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.gateway.list() shouldHaveSize 1
        }
    }

    test("branch 를 생략한 추가는 .gitmodules 에 branch 항목을 만들지 않는다") {
        val origin = seedRepository(CHILD_FILE)
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            val added = fixture.gateway.add(origin.absolutePath, SUBMODULE_PATH)

            added.state.initialized shouldBe true
            fixture.modulesText() shouldContain SUBMODULE_PATH
            fixture.modulesText() shouldNotContain MODULES_BRANCH_ENTRY
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
        }
    }

    test("추가가 실패하면 .gitmodules 와 생성된 디렉터리를 정리한다") {
        val missingOrigin = File(tempdir(), "없는-원격").absolutePath
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            val failure = shouldThrow<UndineException> {
                fixture.gateway.add(missingOrigin, SUBMODULE_PATH)
            }

            failure.cause?.message.orEmpty() shouldNotContain missingOrigin
            fixture.file(GIT_MODULES).exists() shouldBe false
            fixture.file(SUBMODULE_PATH).exists() shouldBe false
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe false
            fixture.configSubsections().shouldBeEmpty()
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("워킹트리 밖을 가리키는 경로 추가는 거부하고 저장소 밖 파일을 지킨다") {
        val origin = seedRepository(CHILD_FILE)
        val (work, sentinel) = repositoryBesideSentinel()
        openParent(work).use { fixture ->
            val violation = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.add(origin.absolutePath, ESCAPING_ADD_PATH)
            }

            violation.message.orEmpty() shouldContain ESCAPE_REASON
            sentinel.readText() shouldBe SENTINEL_CONTENT
            fixture.modulesText() shouldBe ""
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("심볼릭 링크로 워킹트리 밖을 나가는 경로 추가는 거부하고 저장소 밖 파일을 지킨다") {
        val origin = seedRepository(CHILD_FILE)
        val (work, sentinel) = repositoryBesideSentinel()
        openParent(work).use { fixture ->
            // `..` 없이도 밖으로 나간다 — 정규화가 링크를 따라가야만 드러나는 이탈이다.
            Files.createSymbolicLink(fixture.file(ESCAPING_LINK).toPath(), sentinel.parentFile.toPath())

            val violation = shouldThrow<UndineException.StateViolation> {
                fixture.gateway.add(origin.absolutePath, ESCAPING_LINK)
            }

            violation.message.orEmpty() shouldContain ESCAPE_REASON
            sentinel.readText() shouldBe SENTINEL_CONTENT
            fixture.gateway.list().shouldBeEmpty()
        }
    }

    test("추가 되돌리기는 한 단계가 실패해도 남은 보상을 모두 시도한다") {
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            // 추가가 절반만 적용된 상태를 만든다 — 워킹트리·하위 git 디렉터리·.gitmodules.
            val submoduleWork = fixture.file(SUBMODULE_PATH).also(File::mkdirs)
            File(submoduleWork, CHILD_FILE).writeText("절반만 붙은 내용\n")
            fixture.moduleGitDirectory(SUBMODULE_PATH).mkdirs()
            fixture.file(GIT_MODULES).writeText("[submodule \"$SUBMODULE_PATH\"]\n\tpath = $SUBMODULE_PATH\n")

            val failure = IOException("서브모듈 추가 실패")
            withoutWritePermission(submoduleWork) {
                rollback.restoreAfter(failure)

                failure.suppressed.toList().shouldNotBeEmpty()
                fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe false
                fixture.file(GIT_MODULES).exists() shouldBe false
            }
        }
    }

    test("추가 되돌리기는 .gitmodules 의 unstaged 수정을 다시 unstaged 로 되돌린다") {
        openParent(repositoryTrackingModules()).use { fixture ->
            fixture.file(GIT_MODULES).writeText(UNSTAGED_MODULES)
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            fixture.halfApplyModulesEntry()

            rollback.restoreAfter(IOException("서브모듈 추가 실패"))

            fixture.modulesText() shouldBe UNSTAGED_MODULES
            val status = fixture.status()
            status.modified shouldBe setOf(GIT_MODULES)
            status.changed.shouldBeEmpty()
            status.added.shouldBeEmpty()
        }
    }

    test("추가 되돌리기는 워킹트리에서 지워진 .gitmodules 를 다시 지우고 인덱스 엔트리는 남긴다") {
        openParent(repositoryTrackingModules()).use { fixture ->
            fixture.file(GIT_MODULES).delete() shouldBe true
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            fixture.halfApplyModulesEntry()

            rollback.restoreAfter(IOException("서브모듈 추가 실패"))

            fixture.file(GIT_MODULES).exists() shouldBe false
            fixture.indexPaths() shouldBe listOf(GIT_MODULES, PARENT_FILE)
            val status = fixture.status()
            status.missing shouldBe setOf(GIT_MODULES)
            status.removed.shouldBeEmpty()
        }
    }

    test("추가 되돌리기는 덮어쓴 gitlink 와 설정 섹션을 호출 전 값으로 되돌린다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val originalUrl = fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            val originalGitlink = fixture.gitlinkId(SUBMODULE_PATH).shouldNotBeNull()
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            // 추가가 남의 설정을 덮어쓰고 gitlink 까지 건드린, 절반만 적용된 상태.
            fixture.overwriteSubmoduleConfig()
            fixture.repository.removeIndexEntry(SUBMODULE_PATH)

            rollback.restoreAfter(IOException("서브모듈 추가 실패"))

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY) shouldBe originalUrl
            fixture.configValue(SUBMODULE_PATH, CONFIG_ACTIVE_KEY).shouldBeNull()
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe originalGitlink
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
        }
    }

    test("추가 되돌리기는 설정 저장이 실패하면 메모리 설정을 디스크에 맞추고 그 뒤 정리를 하지 않는다") {
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            fixture.seedSubmoduleConfig()
            // 캡처 시점에는 gitlink 가 없다 — 되돌리기는 이 부재까지 되돌려야 한다.
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            // 추가가 남의 설정을 덮어쓰고 gitlink·워킹트리·하위 git 디렉터리·.gitmodules 까지 만든 상태.
            fixture.overwriteSubmoduleConfig()
            fixture.halfApplyGitlink()
            fixture.halfApplyAddArtifacts()

            val failure = IOException("서브모듈 추가 실패")
            fixture.withUnwritableConfig {
                rollback.restoreAfter(failure)

                failure.suppressed.toList().shouldNotBeEmpty()
                // 디스크에 못 썼으므로 메모리도 디스크와 같아야 한다 — 갈라진 채 두지 않는다.
                fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY) shouldBe FOREIGN_URL
                fixture.configValue(SUBMODULE_PATH, CONFIG_ACTIVE_KEY) shouldBe "true"
            }
            // 설정을 못 되돌렸으면 gitlink 도 파일도 건드리지 않는다 — 부분 적용을 더 키우지 않는다.
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe FOREIGN_GITLINK
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe true
            fixture.modulesText() shouldContain SUBMODULE_PATH
        }
    }

    test("설정 복원이 성공해야 추가 되돌리기가 나머지 보상을 진행한다") {
        openParent(seedRepository(PARENT_FILE)).use { fixture ->
            fixture.seedSubmoduleConfig()
            val rollback = SubmoduleAddRollback.capture(fixture.repository, SUBMODULE_PATH)
            fixture.overwriteSubmoduleConfig()
            fixture.halfApplyGitlink()
            fixture.halfApplyAddArtifacts()

            rollback.restoreAfter(IOException("서브모듈 추가 실패"))

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY) shouldBe EXISTING_URL
            fixture.configValue(SUBMODULE_PATH, CONFIG_ACTIVE_KEY).shouldBeNull()
            // 캡처 시점에 없던 gitlink 는 부재로 되돌아간다 — 남기면 인덱스가 유령 서브모듈을 가리킨다.
            fixture.gitlinkId(SUBMODULE_PATH).shouldBeNull()
            fixture.file(SUBMODULE_PATH).exists() shouldBe false
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe false
            fixture.file(GIT_MODULES).exists() shouldBe false
        }
    }

    test("이미 서브모듈이 있는 경로에 추가가 실패해도 호출 전 상태가 그대로 남는다") {
        val missingOrigin = File(tempdir(), "없는-원격").absolutePath
        openParent(repositoryWithSubmodule()).use { fixture ->
            val originalUrl = fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY).shouldNotBeNull()
            val originalGitlink = fixture.gitlinkId(SUBMODULE_PATH).shouldNotBeNull()
            val originalModules = fixture.modulesText()

            shouldThrow<UndineException> { fixture.gateway.add(missingOrigin, SUBMODULE_PATH) }

            fixture.configValue(SUBMODULE_PATH, CONFIG_URL_KEY) shouldBe originalUrl
            fixture.gitlinkId(SUBMODULE_PATH) shouldBe originalGitlink
            fixture.modulesText() shouldBe originalModules
            fixture.file("$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            fixture.moduleGitDirectory(SUBMODULE_PATH).exists() shouldBe true
            fixture.gateway.list() shouldHaveSize 1
        }
    }

    test("없는 서브모듈 초기화는 NotFound.SUBMODULE 로 보고한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            val notFound = shouldThrow<UndineException.NotFound> {
                fixture.gateway.initialize(MISSING_PATH)
            }

            notFound.kind shouldBe UndineException.NotFound.Kind.SUBMODULE
            notFound.name shouldBe MISSING_PATH
        }
    }

    test("초기화는 조회와 실행 사이에 저장소 전환이 끼어도 다른 저장소를 건드리지 않는다") {
        val first = repositoryWithUninitializedSubmodule()
        val second = repositoryWithUninitializedSubmodule()
        switchRaceFixture(first, second).use { fixture ->
            fixture.open()

            fixture.raceSwitch { fixture.gateway.initialize(SUBMODULE_PATH) }

            File(first, "$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe true
            File(second, "$SUBMODULE_PATH/$CHILD_FILE").exists() shouldBe false
            submodulesOf(second).single().state.initialized shouldBe false
        }
    }

    test("업데이트는 조회와 실행 사이에 저장소 전환이 끼어도 다른 저장소를 건드리지 않는다") {
        val first = repositoryWithSubmodule()
        val second = repositoryWithSubmodule()
        divergeSubmodule(first, SUBMODULE_PATH, CHILD_FILE)
        divergeSubmodule(second, SUBMODULE_PATH, CHILD_FILE)
        switchRaceFixture(first, second).use { fixture ->
            fixture.open()

            fixture.raceSwitch { fixture.gateway.update(SUBMODULE_PATH) }

            submodulesOf(first).single().state.divergedFromRecorded shouldBe false
            submodulesOf(second).single().state.divergedFromRecorded shouldBe true
        }
    }
    test("빈 경로는 사전조건 위반으로 거부한다") {
        openParent(repositoryWithSubmodule()).use { fixture ->
            shouldThrow<IllegalArgumentException> { fixture.gateway.initialize(" ") }
            shouldThrow<IllegalArgumentException> { fixture.gateway.update(" ") }
            shouldThrow<IllegalArgumentException> { fixture.gateway.remove(" ", confirmed = true) }
        }
    }
})
