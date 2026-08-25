package dev.undine.infrastructure.git.signing

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal const val TEMPORARY_FILE_PREFIX = "undine-signing-"
internal const val PAYLOAD_FILE_SUFFIX = ".payload"

/**
 * SSH 서명이 쓰는 임시 파일 경계.
 *
 * 경계를 뽑는 이유는 하나다 — **파일 입출력 실패는 실제 파일 시스템에서 재현할 수 없다.**
 * 쓰기 실패나 한쪽 삭제 실패에서도 임시 파일이 남지 않는지 확인하려면 실패를 넣을 자리가
 * 있어야 한다. 프로덕션 구현은 [SystemSigningTemporaryFiles] 하나뿐이다.
 */
internal interface SigningTemporaryFiles {

    /** 서명 대상 바이트를 담을 빈 임시 파일을 만든다. */
    fun createPayloadFile(): Path

    fun write(path: Path, bytes: ByteArray)

    /** 파일이 없으면 `null`. 있는데 읽지 못하면 [IOException] — 없는 것과 못 읽는 것은 다르다. */
    fun readIfExists(path: Path): String?

    fun deleteIfExists(path: Path)
}

internal object SystemSigningTemporaryFiles : SigningTemporaryFiles {

    override fun createPayloadFile(): Path = Files.createTempFile(TEMPORARY_FILE_PREFIX, PAYLOAD_FILE_SUFFIX)

    override fun write(path: Path, bytes: ByteArray) {
        Files.write(path, bytes)
    }

    override fun readIfExists(path: Path): String? = if (Files.exists(path)) Files.readString(path) else null

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

/**
 * [paths] 를 **각각** 지운다 — 한쪽 삭제가 실패해도 나머지가 남으면 안 된다.
 *
 * 삭제 실패를 호출부로 올리지 않는 이유는, 올리면 성공한 서명이 정리 실패 때문에 실패로
 * 보고되기 때문이다. 남는 파일에는 서명 대상 바이트만 있고 키도 패스프레이즈도 없다.
 */
internal fun SigningTemporaryFiles.deleteIgnoringFailure(paths: List<Path>) {
    paths.forEach { path ->
        try {
            deleteIfExists(path)
        } catch (_: IOException) {
            // 위 주석 참조 — 정리 실패는 결과를 바꾸지 않는다.
        }
    }
}

/**
 * 임시 파일은 [process] 가 **실제로 끝난 것을 확인한 뒤에만** 지운다.
 *
 * 강제 종료가 제한 시간 안에 확인되지 않으면 프로그램은 아직 살아 있고, 죽는 중에 서명 파일을
 * 다시 쓸 수 있다. 그때 지우면 지운 자리에 서명 조각이 다시 생긴다 — 그래서 즉시 지우지 않고
 * 정리를 실제 종료 시점으로 미룬다. 프로그램이 끝내 종료되지 않으면 파일은 남지만, 그 안에는
 * 서명 대상 바이트만 있다.
 */
internal fun SigningTemporaryFiles.deleteWhenSettled(process: Process, paths: List<Path>) {
    if (paths.isEmpty()) return
    if (!process.isAlive) {
        deleteIgnoringFailure(paths)
        return
    }
    process.onExit().thenRun { deleteIgnoringFailure(paths) }
}
