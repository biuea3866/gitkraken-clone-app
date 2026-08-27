package dev.undine.application.preferences

import dev.undine.domain.signing.SigningGateway
import dev.undine.domain.signing.SigningSettings

/**
 * 지금 열린 저장소의 커밋 서명 실효값을 읽는다.
 *
 * **읽기 전용이다.** 서명 설정은 git 설정(`commit.gpgsign`·`gpg.format`·`user.signingkey`)이
 * 실효값이고 앱은 자기 사본을 두지 않는다 — 사본을 만들면 사용자가 `git config` 로 바꾼 값과
 * 어긋난다. 그래서 이 화면 경로에는 쓰기 연산이 없다.
 */
class LoadSigningPreferencesUseCase(
    private val signingGateway: SigningGateway,
) {
    suspend fun execute(): SigningSettings = signingGateway.settings()
}
