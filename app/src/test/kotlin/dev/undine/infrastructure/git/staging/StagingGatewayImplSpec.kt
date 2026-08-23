package dev.undine.infrastructure.git.staging

import dev.undine.domain.AmendConfirmation
import dev.undine.domain.CommitId
import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffLine
import dev.undine.domain.DiffLineType
import dev.undine.domain.RepositoryPath
import dev.undine.domain.StagingGateway
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.io.IOException
import java.util.Collections

private const val MAIN_BRANCH = "main"
private const val FILE_PATH = "a.txt"
private const val OTHER_FILE_PATH = "b.txt"
private const val FIVE_LINES = "line1\nline2\nline3\nline4\nline5\n"
private const val FIXED_NOW = 1_700_000_000_000L

/** 직렬화가 깨졌다면 그 사이에 다른 호출이 끼어들 수 있을 만큼만 임계 구역을 잡아 둔다. */
private const val SERIAL_BOUNDARY_HOLD_MILLIS = 200L

private fun TestConfiguration.initRepository(): Git =
    Git.init().setDirectory(tempdir()).setInitialBranch(MAIN_BRANCH).call()

private fun Git.configureAuthor(name: String = "Undine Tester", email: String = "tester@undine.dev") {
    repository.config.apply {
        setString("user", null, "name", name)
        setString("user", null, "email", email)
        save()
    }
}

private fun Git.writeFile(path: String, content: String): File =
    File(repository.workTree, path).apply { writeText(content) }

private fun Git.commitAll(message: String) {
    add().addFilepattern(".").call()
    commit().setMessage(message).call()
}

private fun Git.headCommit(): String = repository.resolve(Constants.HEAD).name

private fun Git.backupRefs() = repository.refDatabase.getRefsByPrefix(AMEND_BACKUP_REF_PREFIX)

/**
 * 로컬 파일 경로를 원격으로 등록해 현재 브랜치를 push 하고 remote-tracking ref 를 만든다.
 * 네트워크를 타지 않아야 CI 에서 흔들리지 않는다.
 */
private fun Git.pushToLocalRemote(remoteDirectory: File) {
    Git.init().setBare(true).setDirectory(remoteDirectory).call().use { }
    remoteAdd().setName(Constants.DEFAULT_REMOTE_NAME).setUri(URIish(remoteDirectory.absolutePath)).call()
    push()
        .setRemote(Constants.DEFAULT_REMOTE_NAME)
        .setRefSpecs(RefSpec("refs/heads/$MAIN_BRANCH:refs/heads/$MAIN_BRANCH"))
        .call()
    fetch().setRemote(Constants.DEFAULT_REMOTE_NAME).call()
}

/** 업스트림 설정은 push 와 분리한다 — "원격에 올라가 있지만 추적하지 않는" 상태를 만들 수 있어야 한다. */
private fun Git.trackUpstream() {
    repository.config.apply {
        setString("branch", MAIN_BRANCH, "remote", Constants.DEFAULT_REMOTE_NAME)
        setString("branch", MAIN_BRANCH, "merge", "refs/heads/$MAIN_BRANCH")
        save()
    }
}

/**
 * [GitAccess] 는 자기 핸들을 열므로 테스트 저장소 경로로 열고, 끝나면 닫는다.
 * Gateway 는 이 공유 경계를 통해서만 저장소를 만진다.
 */
private suspend fun <T> Git.withGitAccess(block: suspend (GitAccess) -> T): T {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(repository.workTree.path)) { }
    return try {
        block(gitAccess)
    } finally {
        gitAccess.close()
    }
}

private suspend fun <T> Git.withStagingGateway(
    now: Long = FIXED_NOW,
    block: suspend (StagingGateway) -> T,
): T = withGitAccess { gitAccess -> block(StagingGatewayImpl(gitAccess) { now }) }

/** 인덱스에 기록된 blob 내용 — 워킹트리 파일과 구분해서 확인해야 부분 stage 를 검증할 수 있다. */
private fun Repository.indexContentOf(path: String): String {
    val entry = readDirCache().getEntry(path) ?: error("인덱스에 항목이 없습니다: $path")
    return newObjectReader().use { reader -> reader.open(entry.objectId).bytes.toString(Charsets.UTF_8) }
}

private fun Repository.indexObjectIdOf(path: String): String =
    readDirCache().getEntry(path)?.objectId?.name ?: error("인덱스에 항목이 없습니다: $path")

/**
 * 거부 경로 검증용 저장소 상태. amend 가 거부됐다면 세 축(HEAD·인덱스·워킹트리)이 **모두** 그대로여야 한다 —
 * HEAD 만 보면 인덱스를 건드리고 실패한 amend 를 통과시킨다.
 */
private data class RepositorySnapshot(
    val head: String,
    val indexObjectIds: Map<String, String>,
    val workingTreeContents: Map<String, String>,
)

private fun Git.snapshotOf(vararg paths: String) = RepositorySnapshot(
    head = headCommit(),
    indexObjectIds = paths.associateWith { repository.indexObjectIdOf(it) },
    workingTreeContents = paths.associateWith { File(repository.workTree, it).readText() },
)

/** 백업 ref 실패는 두 확인 값 모두에서 amend 를 막아야 한다 — 확인 여부와 무관한 가드다. */
private val BACKUP_FAILURE_CONFIRMATIONS: List<Pair<String, (CommitId) -> AmendConfirmation>> = listOf(
    "확인이 필요 없는 amend" to { _ -> AmendConfirmation.NotRequired },
    "대상을 확인한 amend" to { target -> AmendConfirmation.ConfirmedRemoteTarget(target) },
)

/** 첫 줄만 고치는 hunk — `@@ -1,2 +1,2 @@`. */
private val FIRST_LINE_HUNK = DiffHunk(
    oldStart = 1,
    oldLineCount = 2,
    newStart = 1,
    newLineCount = 2,
    lines = listOf(
        DiffLine(DiffLineType.DELETED, "line1", 1, null, emptyList()),
        DiffLine(DiffLineType.ADDED, "line1x", null, 1, emptyList()),
        DiffLine(DiffLineType.CONTEXT, "line2", 2, 2, emptyList()),
    ),
)

class StagingGatewayImplSpec : FunSpec({

    test("파일을 stage 하면 staged 목록에 나타나고 unstaged 목록에서 사라진다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.writeFile(FILE_PATH, "first\nsecond\n")
            git.status().call().modified shouldContain FILE_PATH

            git.withStagingGateway { gateway -> gateway.stage(listOf(FILE_PATH)) }

            val status = git.status().call()
            status.changed shouldContain FILE_PATH
            status.modified.shouldBeEmpty()
        }
    }

    test("삭제된 파일을 stage 하면 인덱스에서도 삭제로 기록된다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            File(git.repository.workTree, FILE_PATH).delete() shouldBe true

            git.withStagingGateway { gateway -> gateway.stage(listOf(FILE_PATH)) }

            git.status().call().removed shouldContain FILE_PATH
            git.repository.readDirCache().getEntry(FILE_PATH) shouldBe null
        }
    }

    test("추가 경로와 삭제 경로를 한 번에 stage 하면 둘 다 인덱스에 반영된다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.writeFile(OTHER_FILE_PATH, "other\n")
            git.commitAll("초기 커밋")
            File(git.repository.workTree, FILE_PATH).delete() shouldBe true
            git.writeFile(OTHER_FILE_PATH, "other\nchanged\n")

            git.withStagingGateway { gateway -> gateway.stage(listOf(FILE_PATH, OTHER_FILE_PATH)) }

            val status = git.status().call()
            status.removed shouldContain FILE_PATH
            status.changed shouldContain OTHER_FILE_PATH
            status.modified.shouldBeEmpty()
        }
    }

    test("인덱스 갱신이 도중에 실패하면 앞 단계까지 되돌아간다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.writeFile(OTHER_FILE_PATH, "other\n")
            val entryCountBefore = git.repository.readDirCache().entryCount

            shouldThrow<IOException> {
                git.repository.withIndexRestoredOnFailure {
                    git.add().addFilepattern(OTHER_FILE_PATH).call()
                    throw IOException("두 번째 단계 실패")
                }
            }

            git.repository.readDirCache().entryCount shouldBe entryCountBefore
            git.repository.readDirCache().getEntry(OTHER_FILE_PATH) shouldBe null
            git.status().call().untracked shouldContain OTHER_FILE_PATH
        }
    }

    test("Gateway 호출은 GitAccess 의 직렬 경계 안에서 실행된다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, "first\n")
            val events = Collections.synchronizedList(mutableListOf<String>())

            git.withGitAccess { gitAccess ->
                val boundaryEntered = CompletableDeferred<Unit>()
                coroutineScope {
                    launch(Dispatchers.Default) {
                        gitAccess.withRepository {
                            events += "경계 진입"
                            boundaryEntered.complete(Unit)
                            Thread.sleep(SERIAL_BOUNDARY_HOLD_MILLIS)
                            events += "경계 이탈"
                        }
                    }
                    launch(Dispatchers.Default) {
                        boundaryEntered.await()
                        StagingGatewayImpl(gitAccess).stage(listOf(FILE_PATH))
                        events += "stage 완료"
                    }
                }
            }

            events shouldBe listOf("경계 진입", "경계 이탈", "stage 완료")
            git.status().call().added shouldContain FILE_PATH
        }
    }

    test("unstage 후 다시 stage 하면 인덱스 상태가 같다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.writeFile(FILE_PATH, "first\nsecond\n")

            git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                val stagedObjectId = git.repository.indexObjectIdOf(FILE_PATH)

                gateway.unstage(listOf(FILE_PATH))

                git.status().call().changed.shouldBeEmpty()
                git.status().call().modified shouldContain FILE_PATH

                gateway.stage(listOf(FILE_PATH))

                git.repository.indexObjectIdOf(FILE_PATH) shouldBe stagedObjectId
            }
        }
    }

    test("커밋이 없는 저장소에서 unstage 하면 인덱스에서 빠지고 untracked 로 돌아온다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, "first\n")

            git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                git.status().call().added shouldContain FILE_PATH

                gateway.unstage(listOf(FILE_PATH))
            }

            git.repository.readDirCache().getEntry(FILE_PATH) shouldBe null
            git.status().call().untracked shouldContain FILE_PATH
        }
    }

    test("hunk 1개만 stage 하면 인덱스에만 반영되고 워킹트리 파일은 그대로다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.commitAll("초기 커밋")
            val workingTreeContent = "line1x\nline2\nline3\nline4\nline5x\n"
            val file = git.writeFile(FILE_PATH, workingTreeContent)

            git.withStagingGateway { gateway -> gateway.stageHunks(FILE_PATH, listOf(FIRST_LINE_HUNK)) }

            git.repository.indexContentOf(FILE_PATH) shouldBe "line1x\nline2\nline3\nline4\nline5\n"
            file.readText() shouldBe workingTreeContent
            val status = git.status().call()
            status.changed shouldContain FILE_PATH
            status.modified shouldContain FILE_PATH
        }
    }

    test("stage 한 변경을 커밋하면 CommitId 가 새 HEAD 와 일치하고 이력에 반영된다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.writeFile(FILE_PATH, "first\nsecond\n")

            val result = git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                gateway.commit("둘째 줄 추가")
            }

            git.headCommit() shouldBe result.commitId.value
            git.log().setMaxCount(1).call().first().fullMessage shouldBe "둘째 줄 추가"
            git.status().call().isClean shouldBe true
        }
    }

    test("작성자 정보가 없으면 커밋하지 않고 AuthorNotConfigured 를 던진다") {
        initRepository().use { git ->
            git.configureAuthor(name = "", email = "")
            git.writeFile(FILE_PATH, "first\n")

            git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                val indexEntryCount = git.repository.readDirCache().entryCount

                shouldThrow<UndineException.AuthorNotConfigured> { gateway.commit("메시지") }

                git.repository.resolve(Constants.HEAD) shouldBe null
                git.repository.readDirCache().entryCount shouldBe indexEntryCount
            }
        }
    }

    test("스테이징된 변경이 없으면 NothingToCommit 으로 커밋을 거부한다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            val headBefore = git.repository.resolve(Constants.HEAD).name

            git.withStagingGateway { gateway ->
                shouldThrow<UndineException.NothingToCommit> { gateway.commit("변경 없는 커밋") }
            }

            git.headCommit() shouldBe headBefore
        }
    }

    test("빈 메시지로 커밋하면 지정된 StateViolation 으로 거부한다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")

            val exception = git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                shouldThrow<UndineException.StateViolation> { gateway.commit("   ") }
            }

            exception.detail shouldBe "커밋 메시지가 비어 있습니다"
            git.repository.resolve(Constants.HEAD) shouldBe null
        }
    }

    test("업스트림 remote-tracking ref 가 HEAD 를 포함하면 preflight 가 원격 포함으로 판정한다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.pushToLocalRemote(tempdir())
            git.trackUpstream()
            val headBefore = git.headCommit()

            val preflight = git.withStagingGateway { gateway -> gateway.inspectAmend() }

            preflight.target shouldBe CommitId.of(headBefore)
            preflight.existsOnRemote shouldBe true
            git.headCommit() shouldBe headBefore
        }
    }

    test("업스트림이 없으면 원격에 올라가 있어도 preflight 가 원격 미포함으로 판정한다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            // push 는 했지만 branch.*.remote 설정이 없다 — 판정 범위는 업스트림 하나다.
            git.pushToLocalRemote(tempdir())

            val preflight = git.withStagingGateway { gateway -> gateway.inspectAmend() }

            preflight.existsOnRemote shouldBe false
        }
    }

    test("로컬 전용 HEAD 는 preflight 뒤 확인 없이 amend 되고 HEAD 가 새 커밋을 가리킨다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            val amendedCommit = git.headCommit()
            git.writeFile(FILE_PATH, "first\nsecond\n")

            val result = git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                gateway.inspectAmend().existsOnRemote shouldBe false
                gateway.amend("초기 커밋 고침", AmendConfirmation.NotRequired)
            }

            result.commitId.value shouldNotBe amendedCommit
            git.headCommit() shouldBe result.commitId.value
            git.log().call().count() shouldBe 1
        }
    }

    test("원격 포함 대상은 같은 대상을 확인하면 amend 되고 원본 커밋이 백업 ref 로 남는다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.pushToLocalRemote(tempdir())
            git.trackUpstream()
            val amendedCommit = git.headCommit()
            git.writeFile(FILE_PATH, "first\nsecond\n")

            val result = git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                val preflight = gateway.inspectAmend()
                preflight.existsOnRemote shouldBe true
                gateway.amend("초기 커밋 고침", AmendConfirmation.ConfirmedRemoteTarget(preflight.target))
            }

            result.commitId.value shouldNotBe amendedCommit
            git.headCommit() shouldBe result.commitId.value
            git.backupRefs().single().objectId.name shouldBe amendedCommit
        }
    }

    test("원격 포함 대상을 확인 없이 amend 하면 거부하고 HEAD·인덱스·워킹트리를 그대로 둔다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.pushToLocalRemote(tempdir())
            git.trackUpstream()
            val headBefore = git.headCommit()
            val indexBefore = git.repository.indexObjectIdOf(FILE_PATH)
            val workingTreeContent = "first\nsecond\n"
            git.writeFile(FILE_PATH, workingTreeContent)

            val exception = git.withStagingGateway { gateway ->
                shouldThrow<UndineException.AmendConfirmationRequired> {
                    gateway.amend("확인 없는 고치기", AmendConfirmation.NotRequired)
                }
            }

            exception.reason shouldBe UndineException.AmendConfirmationRequired.Reason.NOT_CONFIRMED
            exception.target shouldBe CommitId.of(headBefore)
            git.headCommit() shouldBe headBefore
            git.repository.indexObjectIdOf(FILE_PATH) shouldBe indexBefore
            File(git.repository.workTree, FILE_PATH).readText() shouldBe workingTreeContent
            git.backupRefs().shouldBeEmpty()
        }
    }

    test("preflight 뒤 HEAD 가 바뀌면 이전 대상 확인으로는 amend 할 수 없다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.pushToLocalRemote(tempdir())
            git.trackUpstream()

            val exception = git.withStagingGateway { gateway ->
                val staleTarget = gateway.inspectAmend().target

                // preflight 와 실행 사이에 다른 커밋이 HEAD 가 된다.
                git.writeFile(OTHER_FILE_PATH, "other\n")
                git.commitAll("사이에 낀 커밋")
                // 스냅샷은 끼어든 커밋 **뒤** 상태다 — 거부가 그 상태를 그대로 두는지가 관심사다.
                val before = git.snapshotOf(FILE_PATH, OTHER_FILE_PATH)

                val failure = shouldThrow<UndineException.AmendConfirmationRequired> {
                    gateway.amend("낡은 확인으로 고치기", AmendConfirmation.ConfirmedRemoteTarget(staleTarget))
                }
                git.snapshotOf(FILE_PATH, OTHER_FILE_PATH) shouldBe before
                failure
            }

            exception.reason shouldBe UndineException.AmendConfirmationRequired.Reason.TARGET_MISMATCH
            git.backupRefs().shouldBeEmpty()
        }
    }

    test("다른 대상을 확인한 값으로는 amend 할 수 없다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("첫 커밋")
            val otherCommit = git.headCommit()
            git.writeFile(OTHER_FILE_PATH, "other\n")
            git.commitAll("둘째 커밋")
            git.writeFile(FILE_PATH, "first\nsecond\n")
            val before = git.snapshotOf(FILE_PATH, OTHER_FILE_PATH)

            val exception = git.withStagingGateway { gateway ->
                shouldThrow<UndineException.AmendConfirmationRequired> {
                    gateway.amend("엉뚱한 확인", AmendConfirmation.ConfirmedRemoteTarget(CommitId.of(otherCommit)))
                }
            }

            exception.reason shouldBe UndineException.AmendConfirmationRequired.Reason.TARGET_MISMATCH
            git.snapshotOf(FILE_PATH, OTHER_FILE_PATH) shouldBe before
            git.backupRefs().shouldBeEmpty()
        }
    }

    test("preflight 뒤 원격 포함으로 바뀌면 확인 없는 amend 를 거부한다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.pushToLocalRemote(tempdir())
            git.writeFile(FILE_PATH, "first\nsecond\n")
            val before = git.snapshotOf(FILE_PATH)

            git.withStagingGateway { gateway ->
                // 업스트림이 없어 이 시점 판정은 원격 미포함이다.
                gateway.inspectAmend().existsOnRemote shouldBe false

                git.trackUpstream()

                shouldThrow<UndineException.AmendConfirmationRequired> {
                    gateway.amend("낡은 판정으로 고치기", AmendConfirmation.NotRequired)
                }.reason shouldBe UndineException.AmendConfirmationRequired.Reason.NOT_CONFIRMED
            }

            git.snapshotOf(FILE_PATH) shouldBe before
            git.backupRefs().shouldBeEmpty()
        }
    }

    test("HEAD 가 없는 저장소는 preflight 도 amend 도 StateViolation 이고 ref 를 만들지 않는다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")

            git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))

                shouldThrow<UndineException.StateViolation> { gateway.inspectAmend() }
                    .detail shouldBe "고칠 이전 커밋이 없습니다"
                shouldThrow<UndineException.StateViolation> {
                    gateway.amend("고칠 커밋 없음", AmendConfirmation.NotRequired)
                }.detail shouldBe "고칠 이전 커밋이 없습니다"
            }

            git.repository.resolve(Constants.HEAD) shouldBe null
            git.repository.refDatabase.getRefsByPrefix("refs/undine/").shouldBeEmpty()
        }
    }

    test("예상 밖 JGit 실패는 GitOperationFailed 로 번역한다") {
        initRepository().use { git ->
            git.writeFile(FILE_PATH, "first\n")

            val exception = git.withStagingGateway { gateway ->
                File(git.repository.directory, "index").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
                shouldThrow<UndineException.GitOperationFailed> { gateway.stage(listOf(FILE_PATH)) }
            }

            exception.operation shouldBe "staging.stage"
            exception.cause shouldNotBe null
        }
    }

    test("취소된 코루틴에서 호출하면 CancellationException 이 전파된다") {
        initRepository().use { git ->
            val cancelledJob = Job().apply { cancel() }

            git.withStagingGateway { gateway ->
                shouldThrow<CancellationException> {
                    withContext(cancelledJob) { gateway.stage(listOf(FILE_PATH)) }
                }
            }
        }
    }

    test("번역 헬퍼는 CancellationException 을 그대로 올리고 나머지만 GitOperationFailed 로 바꾼다") {
        shouldThrow<CancellationException> {
            translateGitFailure("staging.probe") { throw CancellationException("취소") }
        }
        shouldThrow<UndineException.NothingToCommit> {
            translateGitFailure("staging.probe") { throw UndineException.NothingToCommit() }
        }
        val exception = shouldThrow<UndineException.GitOperationFailed> {
            translateGitFailure("staging.probe") { throw IOException("실패") }
        }
        exception.operation shouldBe "staging.probe"
    }

    test("대상 경로 뒤에 다른 항목이 있어도 hunk stage 가 인덱스 순서를 지킨다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, FIVE_LINES)
            git.writeFile(OTHER_FILE_PATH, "other\n")
            File(git.repository.workTree, "zz").mkdirs()
            git.writeFile("zz/last.txt", "last\n")
            git.commitAll("초기 커밋")
            git.writeFile(FILE_PATH, "line1x\nline2\nline3\nline4\nline5x\n")

            git.withStagingGateway { gateway -> gateway.stageHunks(FILE_PATH, listOf(FIRST_LINE_HUNK)) }

            val dirCache = git.repository.readDirCache()
            val paths = (0 until dirCache.entryCount).map { dirCache.getEntry(it).pathString }
            paths shouldBe paths.sorted()
            paths shouldBe listOf(FILE_PATH, OTHER_FILE_PATH, "zz/last.txt")
            git.repository.indexContentOf(FILE_PATH) shouldBe "line1x\nline2\nline3\nline4\nline5\n"
            git.repository.indexContentOf(OTHER_FILE_PATH) shouldBe "other\n"
            git.repository.indexContentOf("zz/last.txt") shouldBe "last\n"
        }
    }

    test("amend 는 고치기 전 커밋을 백업 ref 로 남긴다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            val amendedCommit = git.headCommit()
            git.writeFile(FILE_PATH, "first\nsecond\n")

            val result = git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                gateway.amend("초기 커밋 고침", AmendConfirmation.NotRequired)
            }

            val backups = git.backupRefs()
            backups shouldHaveSize 1
            backups.single().name shouldBe "$AMEND_BACKUP_REF_PREFIX$MAIN_BRANCH-$FIXED_NOW-${amendedCommit.take(8)}"
            backups.single().objectId.name shouldBe amendedCommit
            result.commitId.value shouldNotBe amendedCommit
        }
    }

    test("amend 가 아닌 커밋은 백업 ref 를 만들지 않는다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            git.writeFile(FILE_PATH, "first\nsecond\n")

            git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                gateway.commit("두번째 커밋")
            }

            git.repository.refDatabase.getRefsByPrefix("refs/undine/").shouldBeEmpty()
        }
    }

    test("같은 시각에 두 번 amend 해도 복구 지점이 둘 다 남는다") {
        initRepository().use { git ->
            git.configureAuthor()
            git.writeFile(FILE_PATH, "first\n")
            git.commitAll("초기 커밋")
            val firstTarget = git.headCommit()
            git.writeFile(FILE_PATH, "first\nsecond\n")

            // 시각을 고정해 이름 충돌 조건을 강제한다 — 커밋 약어가 없으면 앞선 백업이 덮어써진다.
            val secondTarget = git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                gateway.amend("한 번 고침", AmendConfirmation.NotRequired).commitId.value
            }
            git.writeFile(FILE_PATH, "first\nsecond\nthird\n")
            git.withStagingGateway { gateway ->
                gateway.stage(listOf(FILE_PATH))
                gateway.amend("두 번 고침", AmendConfirmation.NotRequired)
            }

            val backups = git.backupRefs()
            backups shouldHaveSize 2
            backups.map { it.objectId.name }.toSet() shouldBe setOf(firstTarget, secondTarget)
        }
    }

    BACKUP_FAILURE_CONFIRMATIONS.forEach { (label, confirmationFor) ->
        test("백업 ref 를 만들지 못하면 ${label}도 실행하지 않고 HEAD·인덱스·워킹트리를 그대로 둔다") {
            initRepository().use { git ->
                git.configureAuthor()
                git.writeFile(FILE_PATH, "first\n")
                git.commitAll("초기 커밋")
                val headBefore = git.headCommit()
                git.writeFile(FILE_PATH, "first\nsecond\n")
                val before = git.snapshotOf(FILE_PATH)
                // 백업 ref 경로를 디렉터리로 선점해 ref 생성을 실패시킨다.
                val blocker = git.repository.updateRef(
                    "$AMEND_BACKUP_REF_PREFIX$MAIN_BRANCH-$FIXED_NOW-${headBefore.take(8)}/blocker",
                )
                blocker.setNewObjectId(git.repository.resolve(Constants.HEAD))
                blocker.setForceUpdate(true)
                blocker.update()

                val exception = shouldThrow<UndineException.StateViolation> {
                    git.withStagingGateway { gateway ->
                        gateway.amend("고치기 시도", confirmationFor(CommitId.of(headBefore)))
                    }
                }

                // 다른 원인의 StateViolation 으로 통과하지 않게 실패 사유를 못 박는다.
                exception.detail shouldContain "amend 대상을 백업하지 못해"
                git.snapshotOf(FILE_PATH) shouldBe before
            }
        }
    }
})
