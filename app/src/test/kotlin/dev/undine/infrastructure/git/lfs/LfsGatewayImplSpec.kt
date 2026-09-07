package dev.undine.infrastructure.git.lfs

import dev.undine.domain.RepositoryPath
import dev.undine.domain.lfs.LfsCommandOutcome
import dev.undine.domain.lfs.LfsDownloadEstimate
import dev.undine.domain.lfs.LfsGateway
import dev.undine.domain.lfs.LfsLock
import dev.undine.domain.lfs.LfsLockState
import dev.undine.domain.lfs.LfsObject
import dev.undine.domain.lfs.LfsObjectState
import dev.undine.domain.lfs.LfsResult
import dev.undine.domain.lfs.LfsTrackingRule
import dev.undine.infrastructure.git.diff.initRepository
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

private const val PATTERN = "*.psd"
private const val LFS_ATTRIBUTES = "filter=lfs diff=lfs merge=lfs -text"
private const val FAILURE_EXIT_CODE = 2

/** 겹칠 기회를 여러 번 준다 — 경합은 한 번에 재현되지 않는다. */
private const val CONCURRENT_ROUNDS = 8

/** 경로 확정이 락 뒤로 밀리면 두 요청이 서로를 기다린다. 매달리지 말고 이 안에 실패해야 한다. */
private const val REPOSITORY_SWITCH_TIMEOUT_MILLIS = 30_000L

/**
 * 실제 임시 저장소 위에서 Gateway 를 검증한다. **시스템 `git-lfs` 는 부르지 않는다** —
 * CLI 경계는 기록 대역([RecordingLfsCommandRunner])이 대신하고, 프로세스 경계 자체는
 * [ProcessLfsCommandRunnerSpec] 이 본다.
 *
 * `.gitattributes` 검증의 축은 하나다 — **어떤 경로로도 사용자 파일이 사라지지 않는다.**
 */
class LfsGatewayImplSpec : FunSpec({

    test("추적 규칙을 더하면 .gitattributes 에 반영되고 규칙 목록으로 올라온다") {
        val work = repositoryIn(tempdir())

        val rules = gatewayIn(work).track(PATTERN)

        rules shouldContainExactly listOf(LfsTrackingRule(PATTERN))
        attributesIn(work) shouldBe "$PATTERN filter=lfs diff=lfs merge=lfs -text\n"
    }

    test("원래 있던 .gitattributes 는 규칙을 더했다 빼면 추가 전 바이트와 같다") {
        val work = repositoryIn(tempdir())
        val original = "# 사용자 주석\r\n*.md text\n*.txt text"
        writeAttributes(work, original)
        val gateway = gatewayIn(work)

        gateway.track(PATTERN)
        gateway.untrack(PATTERN) shouldBe emptyList()

        attributesIn(work) shouldBe original
    }

    test("원래 없던 .gitattributes 는 규칙을 뺀 뒤 0바이트 파일로 남는다") {
        val work = repositoryIn(tempdir())
        val gateway = gatewayIn(work)

        gateway.track(PATTERN)
        gateway.untrack(PATTERN)

        // 지우지 않는다 — 남는 빈 파일은 사용자가 지울 수 있지만 지운 사용자 파일은 되돌릴 수 없다.
        attributesFile(work).exists() shouldBe true
        attributesIn(work) shouldBe ""
    }

    test("원래 있던 0바이트 .gitattributes 는 규칙 제거 후에도 삭제되지 않는다") {
        val work = repositoryIn(tempdir())
        writeAttributes(work, "")
        val gateway = gatewayIn(work)

        gateway.track(PATTERN)
        gateway.untrack(PATTERN)

        attributesFile(work).exists() shouldBe true
        attributesIn(work) shouldBe ""
    }

    test("우리 규칙 외에 주석·다른 규칙이 남아 있으면 그 내용이 그대로 유지된다") {
        val work = repositoryIn(tempdir())
        writeAttributes(work, "# 주석\r\n*.md text\r\n")
        val gateway = gatewayIn(work)

        gateway.track(PATTERN)
        gateway.untrack(PATTERN)

        attributesIn(work) shouldBe "# 주석\r\n*.md text\r\n"
    }

    test("없던 .gitattributes 에 규칙 제거를 요청해도 파일을 만들지 않는다") {
        val work = repositoryIn(tempdir())

        gatewayIn(work).untrack(PATTERN) shouldBe emptyList()

        // 부재를 0바이트 파일로 바꾸면 "없음" 이 값이 된다.
        attributesFile(work).exists() shouldBe false
    }

    test("저장소 A 에서 파일을 만든 뒤 B 로 전환해 규칙을 제거해도 B 의 사용자 파일이 남는다") {
        val first = repositoryIn(tempdir())
        val second = repositoryIn(tempdir())
        val userContent = "# B 의 사용자 규칙\n*.md text\n"
        writeAttributes(second, userContent)
        val access = GitAccess()
        val gateway = LfsGatewayImpl(access, RecordingLfsCommandRunner())

        access.open(RepositoryPath(first.path)) { }
        gateway.track(PATTERN)
        access.open(RepositoryPath(second.path)) { }
        gateway.untrack(PATTERN)

        attributesIn(second) shouldBe userContent
        attributesIn(first) shouldBe "$PATTERN filter=lfs diff=lfs merge=lfs -text\n"
    }

    test("편집을 기다리는 동안 저장소가 바뀌어도 각 요청은 자기 저장소만 고친다") {
        val first = repositoryIn(tempdir())
        val second = repositoryIn(tempdir())
        val userContent = "# B 의 사용자 주석\n$PATTERN $LFS_ATTRIBUTES\n"
        writeAttributes(second, userContent)
        // 두 요청이 **모두 경로를 확정할 때까지** 아무도 진행하지 못하게 한다. 확정이 락 안에서
        // 일어나면 뒤에 온 요청은 여기까지 오지도 못하고, 그때 이 테스트는 시간 초과로 실패한다.
        val resolved = List(2) { CompletableDeferred<Unit>() }
        val slot = AtomicInteger()
        val targets = listOf(first.toPath(), second.toPath())
        val editor = LfsAttributesEditor {
            val index = slot.getAndIncrement()
            resolved[index].complete(Unit)
            resolved.forEach { arrival -> arrival.await() }
            targets[index]
        }

        withTimeout(REPOSITORY_SWITCH_TIMEOUT_MILLIS) {
            coroutineScope {
                launch { editor.track("*.a") }
                launch { editor.untrack(PATTERN) }
            }
        }

        attributesIn(first) shouldBe "*.a $LFS_ATTRIBUTES\n"
        attributesIn(second) shouldBe "# B 의 사용자 주석\n"
    }

    test("공백·탭·줄바꿈이 섞인 패턴은 다듬지 않고 거부하며 파일을 건드리지 않는다") {
        val work = repositoryIn(tempdir())
        val original = "# 사용자 주석\n*.md text\n"
        writeAttributes(work, original)
        val gateway = gatewayIn(work)

        listOf("*.psd extra", "*.psd\ttext", "*.psd\n", " *.psd", "").forEach { rejected ->
            shouldThrow<IllegalArgumentException> { gateway.track(rejected) }
            shouldThrow<IllegalArgumentException> { gateway.untrack(rejected) }
        }

        // 조용한 변형은 나중에 "왜 안 지워지지" 로 돌아온다 — 거부한 입력은 원본 바이트를 남긴다.
        attributesIn(work) shouldBe original
    }

    test("LFS 를 쓰지 않는 저장소는 빈 추적 규칙과 빈 객체 목록을 돌려준다") {
        val work = repositoryIn(tempdir())
        val gateway = gatewayIn(work)

        gateway.trackedRules().shouldBeEmpty()
        gateway.objects().shouldBeInstanceOf<LfsResult.Available<List<LfsObject>>>().value.shouldBeEmpty()
    }

    test("git-lfs 미설치는 명시적인 상태로 올라온다") {
        val gateway = gatewayIn(
            repositoryIn(tempdir()),
            RecordingLfsCommandRunner(fallback = LfsCommandOutcome.NotInstalled),
        )

        gateway.installation() shouldBe LfsResult.NotInstalled
        gateway.objects() shouldBe LfsResult.NotInstalled
    }

    test("프로세스 시작 실패는 미설치와 다른 값으로 올라온다") {
        val detail = "권한이 거부되었습니다"
        val gateway = gatewayIn(
            repositoryIn(tempdir()),
            RecordingLfsCommandRunner(fallback = LfsCommandOutcome.StartFailed(detail)),
        )

        gateway.installation().shouldBeInstanceOf<LfsResult.StartFailed>().detail shouldBe detail
    }

    test("비정상 종료는 표준 오류를 실은 명령 실패로 올라온다") {
        val gateway = gatewayIn(
            repositoryIn(tempdir()),
            RecordingLfsCommandRunner(fallback = LfsCommandOutcome.Completed(FAILURE_EXIT_CODE, "", "무언가 잘못됨")),
        )

        val failure = gateway.objects().shouldBeInstanceOf<LfsResult.CommandFailed>()

        failure.exitCode shouldBe FAILURE_EXIT_CODE
        failure.detail shouldContain "무언가 잘못됨"
    }

    test("알 수 없는 출력은 빈 목록이 아니라 파싱 실패로 올라온다") {
        val gateway = gatewayIn(repositoryIn(tempdir()), completing("무슨 말인지 모를 출력"))

        gateway.objects().shouldBeInstanceOf<LfsResult.OutputUnrecognized>()
        gateway.installation().shouldBeInstanceOf<LfsResult.OutputUnrecognized>()
    }

    test("객체 상태는 포인터만 있는 파일과 내려받은 파일을 구분해 보고한다") {
        val gateway = gatewayIn(repositoryIn(tempdir()), completing("aaa * kept.psd\nbbb - pointer.psd"))

        gateway.objects().shouldBeInstanceOf<LfsResult.Available<List<LfsObject>>>().value shouldContainExactly listOf(
            LfsObject("aaa", "kept.psd", LfsObjectState.DOWNLOADED),
            LfsObject("bbb", "pointer.psd", LfsObjectState.POINTER_ONLY),
        )
    }

    test("크기 조회는 받을 객체와 합계만 돌려주고 다운로드를 시작하지 않는다") {
        val runner = completing("aaa * kept.psd (10 B)\nbbb - pointer.psd (2 KB)\nccc - other.mov (1 KB)")
        val gateway = gatewayIn(repositoryIn(tempdir()), runner)

        val estimate = gateway.pendingDownload()
            .shouldBeInstanceOf<LfsResult.Available<LfsDownloadEstimate>>().value

        estimate.objects.map { entry -> entry.path } shouldContainExactly listOf("pointer.psd", "other.mov")
        estimate.totalBytes shouldBe 3_000L
        // 수신은 별도 호출이다 — 조회만으로 fetch 가 돌면 사용자 확인 없이 대역폭을 쓴다.
        runner.executed.flatten() shouldContainExactly LfsCommand.LS_FILES_WITH_SIZE.arguments
    }

    test("다운로드는 fetch 를 부르고 진행 상황을 한 줄씩 콜백으로 넘긴다") {
        val runner = RecordingLfsCommandRunner(progressLines = listOf("받는 중 1/2", "받는 중 2/2"))
        val gateway = gatewayIn(repositoryIn(tempdir()), runner)
        val progress = mutableListOf<String>()

        gateway.download { line -> progress += line } shouldBe LfsResult.Available(Unit)

        runner.executed shouldContainExactly listOf(LfsCommand.FETCH.arguments)
        progress shouldContainExactly listOf("받는 중 1/2", "받는 중 2/2")
    }

    test("사용자가 거절하면 다운로드를 부르지 않으므로 CLI 도 돌지 않는다") {
        val runner = completing("bbb - pointer.psd (2 KB)")
        val gateway = gatewayIn(repositoryIn(tempdir()), runner)

        gateway.pendingDownload()
        // 거절은 Gateway 결과 값이 아니라 '다음 호출을 하지 않는 것' 이다.

        runner.executed.none { arguments -> arguments == LfsCommand.FETCH.arguments } shouldBe true
    }

    test("잠금을 지원하는 서버는 경로와 소유자 목록을 돌려준다") {
        val output = """[{"id":"1","path":"a.psd","owner":{"name":"alice"}}]"""
        val gateway = gatewayIn(repositoryIn(tempdir()), completing(output))

        val state = gateway.locks().shouldBeInstanceOf<LfsResult.Available<LfsLockState>>().value

        state.shouldBeInstanceOf<LfsLockState.Active>().locks shouldContainExactly listOf(LfsLock("a.psd", "alice"))
    }

    test("잠금을 지원하지 않는 서버는 사유를 실은 비활성 상태로 보고된다") {
        val detail = """Remote "origin" does not support the Git LFS locking API."""
        val gateway = gatewayIn(
            repositoryIn(tempdir()),
            RecordingLfsCommandRunner(fallback = LfsCommandOutcome.Completed(2, "", detail)),
        )

        val state = gateway.locks().shouldBeInstanceOf<LfsResult.Available<LfsLockState>>().value

        state.shouldBeInstanceOf<LfsLockState.Inactive>().detail shouldContain "locking API"
    }

    test("잠금 조회가 그냥 실패하면 비활성이 아니라 실패로 올라온다") {
        // 미지원과 실패를 한 값으로 접으면 사용자는 빈 목록을 보고 "잠금이 없다" 고 믿는다.
        val gateway = gatewayIn(
            repositoryIn(tempdir()),
            RecordingLfsCommandRunner(
                fallback = LfsCommandOutcome.Completed(FAILURE_EXIT_CODE, "", "fatal: could not read Username"),
            ),
        )

        val failure = gateway.locks().shouldBeInstanceOf<LfsResult.CommandFailed>()

        failure.exitCode shouldBe FAILURE_EXIT_CODE
        failure.detail shouldContain "could not read Username"
    }

    test("동시에 두 패턴을 추적해도 한쪽이 다른 쪽을 삼키지 않는다") {
        val work = repositoryIn(tempdir())
        val gateway = gatewayIn(work)
        val pairs = (1..CONCURRENT_ROUNDS).map { round -> "*.a$round" to "*.b$round" }

        pairs.forEach { (first, second) ->
            coroutineScope {
                launch { gateway.track(first) }
                launch { gateway.track(second) }
            }
        }

        // 읽고-고쳐-쓰는 구간이 겹치면 나중 쓰기가 먼저 넣은 규칙을 지운 채 덮는다.
        val patterns = gateway.trackedRules().map { rule -> rule.pattern }
        patterns shouldContainExactlyInAnyOrder pairs.flatMap { (first, second) -> listOf(first, second) }
    }

    test("한쪽이 추적을 더하고 다른 쪽이 다른 패턴을 뺄 때 두 결과가 모두 남는다") {
        val work = repositoryIn(tempdir())
        val comment = "# 사용자 주석\n"
        val removed = (1..CONCURRENT_ROUNDS).map { round -> "*.old$round" }
        writeAttributes(work, comment + removed.joinToString("") { pattern -> "$pattern $LFS_ATTRIBUTES\n" })
        val gateway = gatewayIn(work)
        val added = (1..CONCURRENT_ROUNDS).map { round -> "*.new$round" }

        added.zip(removed).forEach { (add, remove) ->
            coroutineScope {
                launch { gateway.track(add) }
                launch { gateway.untrack(remove) }
            }
        }

        gateway.trackedRules().map { rule -> rule.pattern } shouldContainExactlyInAnyOrder added
        attributesIn(work) shouldContain comment
    }

    test("같은 패턴을 동시에 빼도 사용자의 다른 규칙이 남는다") {
        val work = repositoryIn(tempdir())
        val userRule = "*.md text\n"
        writeAttributes(work, userRule)
        val gateway = gatewayIn(work)

        repeat(CONCURRENT_ROUNDS) {
            gateway.track(PATTERN)
            coroutineScope {
                launch { gateway.untrack(PATTERN) }
                launch { gateway.untrack(PATTERN) }
            }
        }

        attributesIn(work) shouldBe userRule
    }

    test("실행하는 하위 명령은 허용 목록 넷을 벗어나지 않는다") {
        val runner = completing("git-lfs/3.4.1")
        val gateway = gatewayIn(repositoryIn(tempdir()), runner)

        gateway.installation()
        gateway.objects()
        gateway.pendingDownload()
        gateway.download { }
        gateway.locks()
        gateway.trackedRules()
        gateway.track(PATTERN)
        gateway.untrack(PATTERN)

        runner.executed.map { arguments -> arguments.first() }.toSet() shouldBe ALLOWED_SUBCOMMANDS
    }
})

private fun repositoryIn(directory: File): File = directory.also { initRepository(it).close() }

private suspend fun gatewayIn(
    work: File,
    runner: RecordingLfsCommandRunner = RecordingLfsCommandRunner(),
): LfsGateway {
    val access = GitAccess()
    access.open(RepositoryPath(work.path)) { }
    return LfsGatewayImpl(access, runner)
}

private fun completing(standardOutput: String) =
    RecordingLfsCommandRunner(fallback = LfsCommandOutcome.Completed(0, standardOutput, ""))

private fun attributesFile(work: File): File = File(work, GIT_ATTRIBUTES)

private fun writeAttributes(work: File, content: String) {
    Files.write(attributesFile(work).toPath(), content.toByteArray(StandardCharsets.UTF_8))
}

private fun attributesIn(work: File): String =
    String(Files.readAllBytes(attributesFile(work).toPath()), StandardCharsets.UTF_8)
