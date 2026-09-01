package dev.undine.infrastructure.diagnostics

import dev.undine.domain.diagnostics.FileManagerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Path

/** 디렉터리 하나를 여는 플랫폼 호출. 실제 구현을 주입 가능한 한 지점으로 좁혀 테스트를 결정적으로 만든다. */
fun interface DirectoryOpener {

    fun open(directory: File)
}

/**
 * 데스크톱 연동으로 파일 관리자를 띄우는 [FileManagerLauncher] 구현.
 *
 * 외부 프로세스를 직접 띄우지 않는다 — 플랫폼마다 다른 명령(`open`·`xdg-open`·`explorer`)을 앱이
 * 고르는 대신 JDK 가 고르게 두면 프로세스 수명·스트림을 우리가 관리할 필요가 없다.
 *
 * 호출은 블로킹이라 [Dispatchers.IO] 안에서만 하고, 취소는 삼키지 않고 그대로 전파한다.
 */
class DesktopFileManagerLauncher(
    private val directoryOpener: () -> DirectoryOpener? = ::desktopDirectoryOpener,
) : FileManagerLauncher {

    override suspend fun open(directory: Path) {
        val opener = directoryOpener()
            ?: throw IOException("이 환경에서는 파일 관리자를 열 수 없습니다: $directory")
        withContext(Dispatchers.IO) {
            runInterruptible {
                try {
                    opener.open(directory.toFile())
                } catch (rejected: IllegalArgumentException) {
                    // 판정과 실행 사이에 디렉터리가 사라지면 여기로 온다 — 계약대로 실행 실패로 올린다.
                    throw IOException("파일 관리자가 경로를 열지 못했습니다: $directory", rejected)
                } catch (unsupported: UnsupportedOperationException) {
                    throw IOException("이 환경에서는 파일 관리자를 열 수 없습니다: $directory", unsupported)
                }
            }
        }
    }
}

/** 데스크톱 연동을 쓸 수 없는 환경(헤드리스·미지원 데스크톱)에서는 `null` 이다. */
private fun desktopDirectoryOpener(): DirectoryOpener? {
    if (!Desktop.isDesktopSupported()) return null
    val desktop = Desktop.getDesktop()
    return if (desktop.isSupported(Desktop.Action.OPEN)) {
        DirectoryOpener { directory -> desktop.open(directory) }
    } else {
        null
    }
}
