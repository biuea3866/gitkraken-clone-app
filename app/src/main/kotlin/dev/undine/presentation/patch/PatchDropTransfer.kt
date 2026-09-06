package dev.undine.presentation.patch

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException

/**
 * OS 전송 객체에서 파일 위치를 꺼낸다.
 *
 * **modifier 밖에 둔다.** 추출이 Compose 노드 안에 갇히면 "정상 파일 목록 · flavor 없음 · 전송 실패"
 * 세 갈래가 실제 드래그 없이는 확인되지 않는다 — [patchDropOf] 로 이어지는 판정까지 테스트할 수 있게
 * 얇은 함수로 분리한다.
 *
 * 파일이 아닌 드롭(텍스트·이미지)이나 읽을 수 없는 전송은 빈 목록이 되고, [patchDropOf] 가
 * "놓은 것에서 파일을 찾지 못했습니다" 로 안내한다 — 조용히 아무 일도 없는 드롭으로 두지 않는다.
 */
internal fun fileLocationsOf(transferable: Transferable?): List<String> {
    if (transferable?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) != true) return emptyList()
    return try {
        (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
            .orEmpty()
            .filterIsInstance<File>()
            .map(File::getPath)
    } catch (ignoredUnsupported: UnsupportedFlavorException) {
        // 지원한다고 답해 놓고 못 주는 전송이다 — 파일을 못 찾은 것과 같은 안내로 이어진다.
        emptyList()
    } catch (ignoredTransfer: IOException) {
        // 드롭 원본이 이미 사라진 경우다. 사용자에게는 "파일을 찾지 못했다" 와 같은 상황이다.
        emptyList()
    }
}
