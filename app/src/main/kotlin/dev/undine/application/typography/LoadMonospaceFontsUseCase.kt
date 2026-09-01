package dev.undine.application.typography

import dev.undine.domain.typography.MonospaceFontGateway
import dev.undine.domain.typography.MonospaceFontListing

/**
 * 설정 화면에 제시할 고정폭 서체 목록을 읽는다.
 *
 * **읽기 전용이다.** 목록은 사용자가 고르기 쉽게 돕는 것일 뿐이고, 저장된
 * `Settings.monospaceFontFamily` 는 이 경로로 바뀌지 않는다.
 *
 * 결과를 그대로 올린다 — 실패를 빈 목록으로 바꾸면 화면이 "고정폭 서체가 없다" 로 읽어
 * 직접 입력 경로를 안내할 수 없다. 디스패처를 다시 지정하지 않는다. blocking 측정을
 * `Dispatchers.IO` 로 넘기는 것은 Gateway 구현의 책임이다.
 */
class LoadMonospaceFontsUseCase(
    private val monospaceFontGateway: MonospaceFontGateway,
) {
    suspend fun execute(): MonospaceFontListing = monospaceFontGateway.monospaceFamilies()
}
