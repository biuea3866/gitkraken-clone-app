package dev.undine.infrastructure.typography

import dev.undine.domain.typography.FontProbe
import dev.undine.domain.typography.MonospaceFontGateway
import dev.undine.domain.typography.MonospaceFontListing
import dev.undine.domain.typography.monospaceFamiliesOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [MonospaceFontGateway] 의 구현.
 *
 * 열거는 서체 하나씩 폭을 재는 blocking 작업이라 `Dispatchers.IO` 로 옮긴다. 동시 조회 직렬화는
 * **이 Gateway 가 소유한다** — 소비자가 자기 락을 갖지 않는다. 읽기·판단·쓰기(캐시 갱신)가 한
 * 임계구역 안에서 끝나, 두 화면이 동시에 열려도 열거가 중복으로 돌지 않는다.
 *
 * **성공만 캐시한다.** 빈 목록도 성공이므로 재사용하고, 실패는 남기지 않아 다음 조회가 다시
 * 시도한다 — 앱 시작 순간의 일시적 실패가 프로세스 수명 내내 굳으면 사용자가 앱을 껐다 켜야 한다.
 */
class MonospaceFontGatewayImpl(
    private val probe: FontProbe = AwtFontProbe(),
) : MonospaceFontGateway {

    private val enumerationLock = Mutex()

    /** 첫 성공 결과. `null` 은 "아직 성공한 열거가 없다" 는 뜻이라 빈 성공 결과와 섞이지 않는다. */
    private var cachedFamilies: List<String>? = null

    override suspend fun monospaceFamilies(): MonospaceFontListing = withContext(Dispatchers.IO) {
        enumerationLock.withLock {
            when (val reusable = cachedFamilies) {
                null -> enumerate()
                else -> MonospaceFontListing.Available(reusable)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
    private fun enumerate(): MonospaceFontListing = try {
        val monospace = monospaceFamiliesOf(probe.availableFamilies(), probe::glyphWidths)
        cachedFamilies = monospace
        MonospaceFontListing.Available(monospace)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        MonospaceFontListing.Unavailable(failure)
    }
}
