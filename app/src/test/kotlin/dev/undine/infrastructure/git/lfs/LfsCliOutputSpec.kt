package dev.undine.infrastructure.git.lfs

import dev.undine.domain.lfs.LfsLock
import dev.undine.domain.lfs.LfsObject
import dev.undine.domain.lfs.LfsObjectState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private const val DOWNLOADED_OID = "1a2b3c4d"
private const val POINTER_OID = "9f8e7d6c"

/**
 * 문서화된 출력 형식의 **샘플 문자열**로 파서를 검증한다 — 시스템 `git-lfs` 를 부르지 않는다.
 * 이 환경에는 CLI 가 없고, 있더라도 있으면 통과하는 테스트는 통과가 아니다.
 *
 * 핵심은 **인식 못 한 줄이 빈 목록이 아니라 실패가 된다**는 것이다. "객체 0건" 과
 * "출력을 못 읽었다" 를 섞으면 화면이 거짓말한다.
 */
class LfsCliOutputSpec : FunSpec({

    test("version 출력에서 git-lfs 버전 한 줄을 읽는다") {
        LfsCliOutput.versionOf("git-lfs/3.4.1 (GitHub; darwin arm64; go 1.21.1)")
            ?.raw shouldBe "git-lfs/3.4.1 (GitHub; darwin arm64; go 1.21.1)"
    }

    test("git-lfs 로 시작하지 않는 version 출력은 파싱 실패다") {
        LfsCliOutput.versionOf("command not found").shouldBeNull()
        LfsCliOutput.versionOf("").shouldBeNull()
    }

    test("ls-files 출력은 내려받은 객체와 포인터만 있는 객체를 구분한다") {
        val output = """
            $DOWNLOADED_OID * assets/logo.psd
            $POINTER_OID - assets/big video.mov
        """.trimIndent()

        LfsCliOutput.objectsOf(output) shouldContainExactly listOf(
            LfsObject(DOWNLOADED_OID, "assets/logo.psd", LfsObjectState.DOWNLOADED),
            LfsObject(POINTER_OID, "assets/big video.mov", LfsObjectState.POINTER_ONLY),
        )
    }

    test("LFS 를 쓰지 않는 저장소의 빈 출력은 객체 0건이다") {
        LfsCliOutput.objectsOf("")?.shouldBeEmpty()
        LfsCliOutput.objectsOf("\n\n")?.shouldBeEmpty()
    }

    test("인식할 수 없는 ls-files 줄은 빈 목록이 아니라 파싱 실패가 된다") {
        LfsCliOutput.objectsOf("$DOWNLOADED_OID ? assets/logo.psd").shouldBeNull()
        LfsCliOutput.objectsOf("무슨 말인지 모를 출력").shouldBeNull()
        LfsCliOutput.objectsOf("$DOWNLOADED_OID * ok.psd\n깨진 줄").shouldBeNull()
    }

    test("ls-files --size 출력에서 단위를 바이트로 바꿔 읽는다") {
        val output = """
            $DOWNLOADED_OID * a.psd (12 B)
            $POINTER_OID - b.mov (1.5 MB)
        """.trimIndent()

        LfsCliOutput.sizedObjectsOf(output) shouldContainExactly listOf(
            SizedLfsObject(LfsObject(DOWNLOADED_OID, "a.psd", LfsObjectState.DOWNLOADED), bytes = 12),
            SizedLfsObject(LfsObject(POINTER_OID, "b.mov", LfsObjectState.POINTER_ONLY), bytes = 1_500_000),
        )
    }

    test("크기 형식을 읽지 못하면 0 으로 접지 않고 파싱 실패가 된다") {
        LfsCliOutput.sizedObjectsOf("$POINTER_OID - b.mov (1.5 ZB)").shouldBeNull()
        LfsCliOutput.sizedObjectsOf("$POINTER_OID - b.mov").shouldBeNull()
    }

    test("locks --json 에서 경로와 소유자를 읽는다") {
        val output = """
            [
              {"id":"1","path":"assets/logo.psd","owner":{"name":"alice"},"locked_at":"2026-01-01T00:00:00Z"},
              {"id":"2","path":"assets/a \"quoted\" name.mov","owner":{"name":"밥"}}
            ]
        """.trimIndent()

        LfsCliOutput.locksOf(output) shouldContainExactly listOf(
            LfsLock(path = "assets/logo.psd", owner = "alice"),
            LfsLock(path = "assets/a \"quoted\" name.mov", owner = "밥"),
        )
    }

    test("잠금이 0건인 빈 배열과 빈 출력은 목록 0건이다") {
        LfsCliOutput.locksOf("[]")?.shouldBeEmpty()
        LfsCliOutput.locksOf("")?.shouldBeEmpty()
    }

    test("필수 필드가 빠지거나 JSON 이 아닌 잠금 출력은 파싱 실패가 된다") {
        LfsCliOutput.locksOf("""[{"id":"1","owner":{"name":"alice"}}]""").shouldBeNull()
        LfsCliOutput.locksOf("""[{"id":"1","path":"a.psd"}]""").shouldBeNull()
        LfsCliOutput.locksOf("""[{"id":"1","path":"a.psd","owner":"alice"}]""").shouldBeNull()
        LfsCliOutput.locksOf("locking is not supported").shouldBeNull()
        LfsCliOutput.locksOf("""[{"path":"a.psd","owner":{"name":"alice"}}""").shouldBeNull()
    }

    test("잠금 미지원 판정은 그 문구가 있을 때만 참이다") {
        LfsCliOutput.declaresLockingUnsupported(
            """Remote "origin" does not support the Git LFS locking API.""",
        ) shouldBe true
        LfsCliOutput.declaresLockingUnsupported("Server does not support locking") shouldBe true

        // 근거가 없는 실패를 미지원으로 접지 않는다 — 조회 실패는 "잠금 없음" 이 아니다.
        LfsCliOutput.declaresLockingUnsupported("fatal: could not read Username") shouldBe false
        LfsCliOutput.declaresLockingUnsupported("") shouldBe false
    }

    test("허용한 하위 명령은 version·ls-files·locks·fetch 넷뿐이다") {
        LfsCommand.entries.map { command -> command.subcommand }.toSet() shouldBe ALLOWED_SUBCOMMANDS
    }
})
