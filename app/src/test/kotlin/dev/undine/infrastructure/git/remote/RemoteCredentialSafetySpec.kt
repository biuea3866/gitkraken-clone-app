package dev.undine.infrastructure.git.remote

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

private const val REMOTE_SOURCE_PATH = "src/main/kotlin/dev/undine/infrastructure/git/remote"

/** 호스트 키 검증 무력화·자격증명 출력은 되돌릴 수 없는 유출이라 소스 자체를 훑어 막는다. */
private val FORBIDDEN_PATTERNS = listOf(
    "StrictHostKeyChecking",
    "setServerKeyDatabase",
    "AcceptAllServerKeyVerifier",
    "println(",
    "System.out",
    "System.err",
    "printStackTrace",
)

class RemoteCredentialSafetySpec : FunSpec({

    val remoteSourceDirectory = File(REMOTE_SOURCE_PATH)
    val remoteSourceFiles = remoteSourceDirectory.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    test("스캔 대상 remote 소스가 실제로 존재한다") {
        remoteSourceDirectory.isDirectory shouldBe true
        remoteSourceFiles.size shouldBeGreaterThan 0
    }

    test("호스트 키 검증을 끄거나 자격증명을 출력하는 코드가 없다") {
        val violations = remoteSourceFiles.flatMap { source ->
            source.readLines()
                .withIndex()
                .filter { (_, line) -> FORBIDDEN_PATTERNS.any { pattern -> line.contains(pattern) } }
                .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
        }

        violations.shouldBeEmpty()
    }
})
