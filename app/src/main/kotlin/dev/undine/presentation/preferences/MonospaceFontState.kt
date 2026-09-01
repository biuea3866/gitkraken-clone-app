package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.typography.LoadMonospaceFontsUseCase
import dev.undine.domain.typography.MonospaceFontListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 도구 탭의 고정폭 서체 후보 상태 홀더 (compose-ui 규칙 1).
 * [LoadMonospaceFontsUseCase] 만 호출하고 Gateway 를 알지 못한다.
 *
 * **후보는 고르기를 돕는 것일 뿐 저장된 값의 근거가 아니다.** 열거에 실패해도([listing] 이
 * [MonospaceFontListing.Unavailable]) 직접 입력 경로는 그대로 남고, 후보에 없는 저장값도 지우지
 * 않는다 — 아직 설치하지 않았거나 열거가 실패한 것일 수 있다.
 */
@Stable
class MonospaceFontState(
    private val scope: CoroutineScope,
    private val loadMonospaceFonts: LoadMonospaceFontsUseCase,
) {
    /**
     * 마지막 열거 결과. `null` 은 **아직 묻지 않았다**는 뜻이라 "고정폭 서체가 하나도 없다"
     * (빈 [MonospaceFontListing.Available]) 와 섞이지 않는다.
     */
    var listing: MonospaceFontListing? by mutableStateOf(null)
        private set

    /** 고를 수 있는 후보. 열거하지 못했으면 비어 있고, 그래도 직접 입력은 막히지 않는다. */
    val candidates: List<String>
        get() = (listing as? MonospaceFontListing.Available)?.families.orEmpty()

    /** 후보 목록을 얻지 못했는가. 화면은 이 값으로 "직접 입력하세요" 를 안내한다. */
    val isListingUnavailable: Boolean get() = listing is MonospaceFontListing.Unavailable

    /** 설치된 고정폭 서체를 다시 묻는다. 탭 진입 시 배선이 호출한다. */
    fun refresh() {
        scope.launch { listing = loadMonospaceFonts.execute() }
    }
}

/**
 * 화면에 내줄 서체 선택지 — 후보에 **저장된 값을 더한다**.
 *
 * 저장값이 후보에 없다고 목록에서 빼면, 고를 수 있는 것 중에 지금 값이 없어 사용자는 자기가 적어 둔
 * 이름을 잃는다. 순서는 열거 계약과 같은 이름 오름차순을 유지한다.
 */
internal fun monospaceFontChoices(candidates: List<String>, saved: String?): List<String> {
    val savedFamily = saved?.trim()?.takeIf(String::isNotEmpty) ?: return candidates
    return (candidates + savedFamily).distinct().sorted()
}

/** 컴포지션 수명에 묶인 서체 후보 상태. 첫 조합에서 한 번 묻는다. */
@Composable
fun rememberMonospaceFontState(loadMonospaceFonts: LoadMonospaceFontsUseCase): MonospaceFontState {
    val scope = rememberCoroutineScope()
    val state = remember(scope, loadMonospaceFonts) { MonospaceFontState(scope, loadMonospaceFonts) }
    LaunchedEffect(state) { state.refresh() }
    return state
}
