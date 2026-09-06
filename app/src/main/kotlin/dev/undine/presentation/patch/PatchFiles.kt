package dev.undine.presentation.patch

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 파일 선택·저장 대화상자와 실제 읽기·쓰기의 경계.
 *
 * **인터페이스 뒤에 둔다.** 화면이 AWT `FileDialog` 를 직접 열면 화면 테스트가 사람 조작을 기다리며
 * 멈추고, 실제 파일까지 써 버린다. 상태 홀더는 이 계약만 알고 구현은 배선이 준다.
 *
 * 실패는 [IOException] 으로 올린다 — 조용히 `null` 을 돌려주면 화면이 "취소했다" 와 "못 읽었다" 를
 * 구분하지 못한다. 취소는 대화상자 반환값 `null` 이고 그건 실패가 아니다.
 */
interface PatchFiles {

    /** 적용할 패치 하나를 고른다. 취소하면 `null`. */
    fun chooseOpenFile(): Path?

    /** 단일 통합 패치를 저장할 파일을 고른다. 취소하면 `null`. */
    fun chooseSaveFile(defaultName: String): Path?

    /** 커밋당 파일을 저장할 디렉터리를 고른다. 취소하면 `null`. */
    fun chooseDirectory(): Path?

    @Throws(IOException::class)
    fun read(path: Path): ByteArray

    fun exists(path: Path): Boolean

    /**
     * **완성한 뒤에 제자리로 옮긴다.** 대상에 곧바로 쓰면 도중에 실패했을 때 사용자가 갖고 있던 패치
     * 파일이 반쯤 덮인 채로 남는다 — 임시 파일에 다 쓰고 원자적으로 바꿔치기해야 실패해도 기존 내용이
     * 그대로다.
     */
    @Throws(IOException::class)
    fun writeAtomically(path: Path, bytes: ByteArray)

    /** 없으면 아무 일도 하지 않는다 — 되돌리기가 "지울 것이 없다" 를 실패로 보고하지 않게 한다. */
    @Throws(IOException::class)
    fun delete(path: Path)
}

private const val MAC_DIRECTORY_DIALOG_KEY = "apple.awt.fileDialogForDirectories"
private const val TEMPORARY_PREFIX = "undine-patch"
private const val TEMPORARY_SUFFIX = ".tmp"

/**
 * 임시 파일을 지우지 못했다 — 사용자가 고른 폴더에 반쯤 쓰인 패치가 남았다는 뜻이다.
 *
 * 원래 실패 사유를 가리지 않도록 그 실패에 `suppressed` 로 붙어 다닌다. 치울 수 있는 것은 사용자뿐이므로
 * 이 사실을 삼키면 사용자는 자기 폴더에 무엇이 남았는지 알 길이 없다.
 */
internal class PatchTemporaryFileLeftException(val temporary: Path, cause: IOException) :
    IOException("임시 패치 파일을 지우지 못했습니다: $temporary", cause)

/**
 * 임시 파일을 만들어 채우고 제자리로 옮긴다. 도중에 실패하면 임시 파일을 지운다.
 *
 * **지우기까지 실패하면 그 사실을 잃지 않는다** — [PatchTemporaryFileLeftException] 을 원래 실패에
 * `suppressed` 로 붙여 올린다. 원인을 가리지 않으면서 남은 파일을 잃지도 않는 방식이고,
 * `RepositorySessionUseCase` 가 복원 실패를 다루는 방식과 같다.
 *
 * 원시 연산을 인자로 받는 이유는 **쓰기·이동 실패 + 정리 실패** 조합을 실제 파일시스템에서 만들 수 없기
 * 때문이다 — 임시 파일을 만들 수 있는 폴더는 그 파일을 지울 수도 있다. 두 실패가 겹치는 경로를 회귀로
 * 잡으려면 여기서 갈라 두는 수밖에 없다.
 */
internal fun writeThroughTemporary(
    createTemporary: () -> Path,
    fill: (Path) -> Unit,
    moveIntoPlace: (Path) -> Unit,
    removeTemporary: (Path) -> Unit,
) {
    val temporary = createTemporary()
    try {
        fill(temporary)
        moveIntoPlace(temporary)
    } catch (failure: IOException) {
        try {
            removeTemporary(temporary)
        } catch (cleanupFailure: IOException) {
            failure.addSuppressed(PatchTemporaryFileLeftException(temporary, cleanupFailure))
        }
        throw failure
    }
}

/** 실패에 딸려 온 "지우지 못한 임시 파일" 목록. 없으면 빈 목록이다. */
internal fun Throwable.leftoverTemporaries(): List<Path> =
    suppressed.filterIsInstance<PatchTemporaryFileLeftException>().map { it.temporary }

/**
 * AWT `FileDialog` 를 쓰는 실제 구현. 배선(`AppDestinationScreens`)만 이 클래스를 만든다.
 *
 * 디렉터리 선택에 macOS 전용 속성을 켜는 것은 `AppCommands.chooseDirectory` 와 같은 이유다 —
 * 네이티브 디렉터리 선택을 그대로 얻기 위해서이며, 전역 속성이라 끝나면 원래 값으로 되돌린다.
 *
 * **제목을 값이 아니라 조회로 받는다.** 문구를 생성자에 박으면 문구가 바뀔 때 이 인스턴스가 새로
 * 만들어지고, 그것을 키로 기억하는 상태 홀더까지 통째로 갈린다 — 진행 중이던 적용·저장의 결과가
 * 갈 곳을 잃는다. 제목은 대화상자를 **열 때** 최신 카탈로그에서 읽으면 그만이다.
 */
class AwtPatchFiles(
    private val openTitle: () -> String,
    private val saveTitle: () -> String,
    private val directoryTitle: () -> String,
) : PatchFiles {

    override fun chooseOpenFile(): Path? = dialogPath(openTitle(), FileDialog.LOAD, defaultName = null)

    override fun chooseSaveFile(defaultName: String): Path? = dialogPath(saveTitle(), FileDialog.SAVE, defaultName)

    override fun chooseDirectory(): Path? {
        val previous = System.getProperty(MAC_DIRECTORY_DIALOG_KEY)
        System.setProperty(MAC_DIRECTORY_DIALOG_KEY, "true")
        return try {
            dialogPath(directoryTitle(), FileDialog.LOAD, defaultName = null)
        } finally {
            if (previous == null) {
                System.clearProperty(MAC_DIRECTORY_DIALOG_KEY)
            } else {
                System.setProperty(MAC_DIRECTORY_DIALOG_KEY, previous)
            }
        }
    }

    override fun read(path: Path): ByteArray = Files.readAllBytes(path)

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun writeAtomically(path: Path, bytes: ByteArray) {
        val directory = path.parent?.also(Files::createDirectories) ?: Paths.get("")
        writeThroughTemporary(
            // 같은 디렉터리에 만든다 — 다른 파일시스템에 두면 마지막 이동이 원자적일 수 없다.
            createTemporary = { Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX) },
            fill = { temporary -> Files.write(temporary, bytes) },
            moveIntoPlace = { temporary -> moveInto(temporary, path) },
            removeTemporary = { temporary -> Files.deleteIfExists(temporary) },
        )
    }

    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    private fun moveInto(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (ignoredUnsupportedMove: AtomicMoveNotSupportedException) {
            // 파일시스템이 원자적 이동을 지원하지 않는 경우다 — 그때만 일반 이동으로 내려간다.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun dialogPath(title: String, mode: Int, defaultName: String?): Path? {
        val dialog = FileDialog(null as Frame?, title, mode)
        defaultName?.let { dialog.file = it }
        dialog.isVisible = true
        // 취소하면 둘 중 하나가 비어 온다 — 실패가 아니라 "고르지 않았다" 이므로 null 이다.
        val directory = dialog.directory
        val file = dialog.file
        return if (directory == null || file == null) null else File(directory, file).toPath()
    }
}
