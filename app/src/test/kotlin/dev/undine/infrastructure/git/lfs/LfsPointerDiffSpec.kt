package dev.undine.infrastructure.git.lfs

import dev.undine.application.diff.LoadFileDiffUseCase
import dev.undine.domain.DiffResult
import dev.undine.infrastructure.git.diff.commitAll
import dev.undine.infrastructure.git.diff.diffGateway
import dev.undine.infrastructure.git.diff.id
import dev.undine.infrastructure.git.diff.initRepository
import dev.undine.infrastructure.git.diff.writeFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val POINTER_PATH = "assets/logo.psd"

private const val POINTER_VERSION_LINE = "version https://git-lfs.github.com/spec/v1"
private const val VALID_OID = "4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"

/** LFS 포인터 파일 원문. 실제 `git lfs track` 이 만드는 것과 같은 형태다. */
private val POINTER_CONTENT = "$POINTER_VERSION_LINE\noid sha256:$VALID_OID\nsize 12345\n"

/**
 * LFS 포인터가 기존 diff 경로(`LoadFileDiffUseCase → DiffGatewayImpl → DiffContent → DiffResult`)
 * 에서 **닫힌 사유**로 판정되는지 본다. 포인터 원문이 hunk 로 그려지면 사용자는 그 몇 줄을 파일
 * 내용으로 읽고 "파일이 깨졌다" 고 오해한다.
 */
class LfsPointerDiffSpec : FunSpec({

    test("LFS 포인터 파일의 diff 는 hunk 대신 LFS 포인터 사유로 판정된다") {
        val git = initRepository(tempdir())
        git.writeFile(POINTER_PATH, POINTER_CONTENT)
        val commit = git.commitAll("포인터 추가")
        val loadFileDiff = LoadFileDiffUseCase(git.diffGateway())

        val result = loadFileDiff.execute(commit.id(), POINTER_PATH)

        result.shouldBeInstanceOf<DiffResult.NotComputed>().reason shouldBe DiffResult.Reason.LFS_POINTER
    }

    test("포인터가 아닌 텍스트 파일은 그대로 hunk 가 계산된다") {
        val git = initRepository(tempdir())
        git.writeFile("readme.md", "첫 줄\n")
        val commit = git.commitAll("텍스트 추가")
        val loadFileDiff = LoadFileDiffUseCase(git.diffGateway())

        loadFileDiff.execute(commit.id(), "readme.md").shouldBeInstanceOf<DiffResult.Computed>()
    }

    test("포인터 선언으로 시작하지 않는 작은 파일은 포인터로 오인되지 않는다") {
        val git = initRepository(tempdir())
        git.writeFile("note.txt", "oid sha256:0000\nsize 1\n")
        val commit = git.commitAll("비슷하지만 다른 파일")
        val loadFileDiff = LoadFileDiffUseCase(git.diffGateway())

        loadFileDiff.execute(commit.id(), "note.txt").shouldBeInstanceOf<DiffResult.Computed>()
    }

    // 선언 한 줄로 판정하면 그 문구를 본문에 담은 텍스트 파일의 진짜 diff 가 숨는다.
    // 사용자는 변경을 못 보고 왜 안 보이는지도 모른다 — 숨기는 쪽이 아니라 보여 주는 쪽으로 틀린다.
    listOf(
        "선언만 있고 oid·size 가 없으면" to "$POINTER_VERSION_LINE\n스펙 문서를 인용한 메모입니다.\n",
        "oid 가 sha256 형식이 아니면" to "$POINTER_VERSION_LINE\noid sha256:짧음\nsize 12345\n",
        "size 가 숫자가 아니면" to "$POINTER_VERSION_LINE\noid sha256:$VALID_OID\nsize 열두줄\n",
        "size 행이 아예 없으면" to "$POINTER_VERSION_LINE\noid sha256:$VALID_OID\n",
        "선언이 첫 줄이 아니면" to "머리말\n$POINTER_VERSION_LINE\noid sha256:$VALID_OID\nsize 1\n",
        "마지막 줄바꿈이 없으면" to "$POINTER_VERSION_LINE\noid sha256:$VALID_OID\nsize 1",
    ).forEach { (description, content) ->
        test("$description 포인터로 판정하지 않고 diff 를 그대로 계산한다") {
            val git = initRepository(tempdir())
            git.writeFile("note.txt", content)
            val commit = git.commitAll("포인터를 닮은 텍스트")
            val loadFileDiff = LoadFileDiffUseCase(git.diffGateway())

            loadFileDiff.execute(commit.id(), "note.txt").shouldBeInstanceOf<DiffResult.Computed>()
        }
    }
})
