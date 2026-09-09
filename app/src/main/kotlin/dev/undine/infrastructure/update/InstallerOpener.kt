package dev.undine.infrastructure.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * 설치 파일을 **OS 기본 동작으로 넘기는** 경계 (결정 D12).
 *
 * dmg 는 마운트되고 msi·deb 는 설치 관리자가 받는다. 앱을 우리가 교체하지 않으므로 권한 승격도
 * 실행 중인 앱 교체도 여기에 없다.
 *
 * 인터페이스로 두는 이유는 `FileManagerLauncher` 와 같다 — 테스트가 개발자·CI 의 데스크톱에서
 * 실제 설치 관리자를 띄우지 않게 한다.
 */
interface InstallerOpener {

    /**
     * [installer] 를 OS 기본 동작으로 연다. 창이 뜰 때까지가 아니라 **넘길 때까지**만 기다린다.
     *
     * 넘기지 못하면 사유가 담긴 `IOException` 을 던진다 — 열리지 않은 것을 성공으로 접지 않는다.
     */
    suspend fun open(installer: Path)
}

/** 파일 하나를 여는 플랫폼 호출. 주입 가능한 한 지점으로 좁혀 테스트를 결정적으로 만든다. */
fun interface DesktopFileOpener {

    fun open(file: File)
}

/**
 * 데스크톱 연동으로 설치 파일을 여는 [InstallerOpener] 구현.
 *
 * 플랫폼별 명령(`open`·`start`·`xdg-open`)을 앱이 고르지 않는다 — JDK 가 고르게 두면 프로세스
 * 수명과 스트림을 우리가 관리할 필요가 없다 (`DesktopFileManagerLauncher` 와 같은 판단).
 */
class DesktopInstallerOpener(
    private val fileOpener: () -> DesktopFileOpener? = ::desktopFileOpener,
) : InstallerOpener {

    override suspend fun open(installer: Path) {
        // 획득 자체가 SecurityException 을 던질 수 있다 — 그것도 여는 데 실패한 것이지 앱이 죽을 일이 아니다.
        val opener = try {
            fileOpener()
        } catch (denied: SecurityException) {
            throw IOException("이 환경에서는 설치 파일을 열 수 없습니다: $installer", denied)
        } ?: throw IOException("이 환경에서는 설치 파일을 열 수 없습니다: $installer")
        withContext(Dispatchers.IO) {
            runInterruptible {
                try {
                    opener.open(installer.toFile())
                } catch (rejected: IllegalArgumentException) {
                    throw IOException("설치 파일을 열지 못했습니다: $installer", rejected)
                } catch (unsupported: UnsupportedOperationException) {
                    throw IOException("이 환경에서는 설치 파일을 열 수 없습니다: $installer", unsupported)
                } catch (denied: SecurityException) {
                    // 정책이 막은 것도 여는 데 실패한 것이다. 게이트웨이의 IOException 경계를 비껴가면 앱이 죽는다.
                    throw IOException("이 환경에서는 설치 파일을 열 수 없습니다: $installer", denied)
                }
            }
        }
    }
}

/** 데스크톱 연동을 쓸 수 없는 환경(헤드리스·미지원 데스크톱)에서는 `null` 이다. */
private fun desktopFileOpener(): DesktopFileOpener? {
    if (!Desktop.isDesktopSupported()) return null
    val desktop = Desktop.getDesktop()
    return if (desktop.isSupported(Desktop.Action.OPEN)) {
        DesktopFileOpener { file -> desktop.open(file) }
    } else {
        null
    }
}
