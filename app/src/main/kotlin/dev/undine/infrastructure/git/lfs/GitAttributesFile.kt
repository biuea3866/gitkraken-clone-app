package dev.undine.infrastructure.git.lfs

import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

internal const val GIT_ATTRIBUTES = ".gitattributes"

/** 새로 만드는 `.gitattributes` 의 권한. 기존 파일이 있으면 그 파일의 권한을 그대로 옮긴다. */
private val NEW_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-r--r--")

/**
 * `.gitattributes` 의 현재 상태.
 *
 * **[Absent] 와 내용이 빈 [Present] 는 끝까지 다른 값이다.** `content.isEmpty()` 로 부재를
 * 추론하면 원래 있던 0바이트 사용자 파일을 없는 것으로 취급하게 된다.
 */
internal sealed interface AttributesContent {

    data object Absent : AttributesContent

    /** [bytes] 는 파일 바이트를 ISO-8859-1 로 디코딩한 **바이트 문자열**이다. */
    data class Present(val bytes: String) : AttributesContent
}

/**
 * 임시 파일을 제자리로 옮기는 경계.
 *
 * 주입 가능하게 둔 이유는 하나다 — **원자 교체를 지원하지 않는 파일시스템에서 원본이 그대로
 * 남는가**를 테스트가 실제로 실행해 봐야 하기 때문이다. 그 경로는 정해 두기만 하고 돌려 보지
 * 않으면 정한 적 없는 것과 같다. 프로덕션 경로는 [ATOMIC_MOVE] 하나뿐이다.
 */
internal fun interface AttributesFileMove {

    /** 원자 교체가 불가능하면 [AtomicMoveNotSupportedException] 을 던진다. */
    fun move(source: Path, target: Path)
}

/** 실제 교체. 파일시스템이 원자 이동을 지원하지 않으면 예외가 올라온다 — 덮어쓰기로 물러서지 않는다. */
internal val ATOMIC_MOVE = AttributesFileMove { source, target ->
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
}

/**
 * `.gitattributes` 를 바이트 그대로 읽고 쓴다.
 *
 * ISO-8859-1 은 바이트 0~255 를 문자와 1:1 로 대응시키므로, 이 문자열을 다시 같은 인코딩으로
 * 쓰면 **원문 바이트가 그대로 복원**된다. UTF-8 로 읽으면 잘못된 바이트가 대체 문자로 바뀌어
 * 손대지 않은 부분까지 훼손된다.
 *
 * 패턴 문자열만 UTF-8 ↔ 바이트 문자열로 변환한다 — 한글 등 비 ASCII 패턴도 파일에는 UTF-8 로
 * 들어가야 하기 때문이다.
 *
 * **쓰기는 세 가지를 지킨다.** 제자리 덮어쓰기를 하지 않고(부분 기록된 파일을 남기지 않는다),
 * 심볼릭 링크를 따라가지 않으며(링크 대상이 저장소 밖 사용자 파일일 수 있다), 읽은 뒤 바뀐 파일을
 * 옛 스냅샷으로 덮지 않는다. 셋 중 하나라도 지킬 수 없으면 **원본을 건드리지 않고 실패**한다 —
 * 사용자 규칙을 반쯤 지운 파일보다 아무것도 안 한 파일이 낫다.
 */
internal object GitAttributesFile {

    fun readIn(workingDirectory: Path): AttributesContent {
        val file = pathIn(workingDirectory)
        rejectSymbolicLink(file)
        // 부재와 읽기 실패를 섞지 않는다 — 권한 거부 같은 실패는 예외로 그대로 올라간다.
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return AttributesContent.Absent
        return AttributesContent.Present(String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
    }

    /**
     * [expected] 를 읽었던 그 내용 그대로일 때만 [bytes] 로 **원자 교체**한다.
     *
     * 내용이 비어도 파일을 남긴다 — 0바이트로 쓴다. 삭제하지 않는 것이 이 기능의 계약이다.
     * 그 사이 파일이 바뀌었으면 쓰지 않고 실패한다 — 조용히 덮으면 사용자가 방금 넣은 규칙이
     * 사라진 것을 한참 뒤에야 안다.
     */
    fun writeIn(
        workingDirectory: Path,
        expected: AttributesContent,
        bytes: String,
        move: AttributesFileMove = ATOMIC_MOVE,
    ) {
        val file = pathIn(workingDirectory)
        rejectSymbolicLink(file)
        if (readIn(workingDirectory) != expected) {
            throw IOException("$GIT_ATTRIBUTES 가 읽은 뒤 바뀌어 덮어쓰지 않았습니다: $file")
        }
        replaceAtomically(file, bytes.toByteArray(StandardCharsets.ISO_8859_1), move)
    }

    fun pathIn(workingDirectory: Path): Path = workingDirectory.resolve(GIT_ATTRIBUTES)

    /** UTF-8 문자열 → 바이트 문자열. */
    fun toByteString(text: String): String =
        String(text.toByteArray(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)

    /** 바이트 문자열 → UTF-8 문자열. */
    fun fromByteString(bytes: String): String =
        String(bytes.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)

    /**
     * 같은 디렉터리의 임시 파일에 전부 기록·동기화한 뒤 한 번의 이동으로 교체한다.
     *
     * 임시 파일을 같은 디렉터리에 두는 이유는 원자 이동이 파일시스템 경계를 넘지 못하기 때문이다.
     * 이동이 원자적일 수 없는 파일시스템이면 **덮어쓰기로 물러서지 않고 실패**한다 — 그 물러섬이
     * 정확히 사용자 규칙을 반쯤 날리는 경로다.
     */
    private fun replaceAtomically(file: Path, bytes: ByteArray, move: AttributesFileMove) {
        val temporary = Files.createTempFile(file.parent, "$GIT_ATTRIBUTES.", ".tmp")
        val written = runCatching {
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(bytes)
                stream.flush()
                // 내용이 디스크에 닿기 전에 이름만 바뀌면 전원이 끊길 때 빈 파일이 남는다.
                stream.fd.sync()
            }
            copyPermissions(from = file, to = temporary)
            move.move(temporary, file)
        }
        written.exceptionOrNull()?.let { failure ->
            Files.deleteIfExists(temporary)
            throw when (failure) {
                is AtomicMoveNotSupportedException ->
                    IOException("$GIT_ATTRIBUTES 를 원자적으로 교체할 수 없어 쓰지 않았습니다: $file", failure)
                else -> failure
            }
        }
    }

    /** 교체가 파일 권한을 바꾸지 않게 한다. 임시 파일은 소유자 전용으로 만들어지기 때문이다. */
    private fun copyPermissions(from: Path, to: Path) {
        if (Files.getFileAttributeView(to, PosixFileAttributeView::class.java) == null) return
        val permissions = if (Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
            Files.getPosixFilePermissions(from, LinkOption.NOFOLLOW_LINKS)
        } else {
            NEW_FILE_PERMISSIONS
        }
        Files.setPosixFilePermissions(to, permissions)
    }

    /**
     * 링크면 손대지 않고 거부한다.
     *
     * 링크를 따라가면 저장소 밖 사용자 파일을 고치게 되고, 링크를 교체하면 사용자가 만든 링크를
     * 소리 없이 없앤다. 둘 다 되돌릴 수 없으므로 어느 쪽도 하지 않는다.
     */
    private fun rejectSymbolicLink(file: Path) {
        if (Files.isSymbolicLink(file)) {
            throw IOException("$GIT_ATTRIBUTES 가 심볼릭 링크라 다루지 않았습니다: $file")
        }
    }
}
