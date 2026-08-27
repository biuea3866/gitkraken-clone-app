package dev.undine.domain

import java.awt.event.KeyEvent

/**
 * 사용자가 바꾼 단축키 한 벌의 **저장 표현**.
 *
 * 화면이 쓰는 단축키 타입(`presentation.palette.Shortcut`)을 여기 두지 않는 이유는 레이어 방향이다 —
 * domain 은 presentation 을 알지 못한다. 그래서 저장 파일이 담을 수 있는 값(키 코드·위치·수식키)만
 * 담고, 키 입력 해석과 표기는 presentation 이 한다.
 *
 * @property keyCode AWT 가상 키 코드(`java.awt.event.KeyEvent.VK_*`). 키 **이름**을 적지 않는 것은
 *   이름이 JVM 로케일을 따라 달라져 다른 환경에서 다시 읽을 때 어긋나기 때문이다.
 * @property modifiers 함께 눌러야 하는 수식키. 비어 있으면 키 하나만 누르는 단축키다.
 * @property keyLocation 같은 키 코드를 쓰는 자리 구분(숫자 키패드 등). 대부분 [KeyEvent.KEY_LOCATION_STANDARD]
 *   이지만, 담지 않으면 키패드로 잡은 단축키가 되살아날 때 **다른 키에 묶인다**.
 */
data class ShortcutBinding(
    val keyCode: Int,
    val modifiers: Set<ShortcutModifierKey> = emptySet(),
    val keyLocation: Int = KeyEvent.KEY_LOCATION_STANDARD,
)

/**
 * 플랫폼 중립 수식키. [PRIMARY] 가 macOS 의 `⌘` 와 그 외 OS 의 `Ctrl` 차이를 흡수하므로,
 * 한 환경에서 저장한 설정 파일을 다른 환경에서 읽어도 뜻이 유지된다.
 */
enum class ShortcutModifierKey {
    PRIMARY,
    SHIFT,
    ALT,
}
