package dev.undine.presentation.i18n

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext

/** 기본 로케일을 바꿔 블록을 실행하고 원래 값으로 되돌린다 — 테스트끼리 전역 상태를 오염시키지 않는다. */
internal fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    return try {
        block()
    } finally {
        Locale.setDefault(previous)
    }
}

/** 시스템 프로퍼티를 세팅해 블록을 실행하고 원래 값(또는 부재)으로 되돌린다. */
internal fun <T> withSystemProperty(name: String, value: String, block: () -> T): T {
    val previous = System.getProperty(name)
    System.setProperty(name, value)
    return try {
        block()
    } finally {
        if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
    }
}

/**
 * 컴포지션을 한 번 돌려 Composable 안에서 읽은 문자열을 돌려준다.
 *
 * Compose UI 테스트 의존성 없이 **런타임만으로** 실제 읽기 경로를 태운다 — 화면 트리를 그리지 않으므로
 * 노드를 만들지 않는 [NoOpApplier] 를 쓴다.
 */
internal fun composeCapturing(content: @Composable ((String) -> Unit) -> Unit): String {
    var captured: String? = null
    val recomposer = Recomposer(EmptyCoroutineContext)
    val composition = Composition(NoOpApplier(), recomposer)
    try {
        composition.setContent { content { value -> captured = value } }
    } finally {
        composition.dispose()
        recomposer.cancel()
    }
    return requireNotNull(captured) { "컴포지션이 문자열을 읽지 못했습니다" }
}

/** 노드를 만들지 않는 Applier — 값 읽기만 검증하므로 트리를 유지할 필요가 없다. */
private class NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
