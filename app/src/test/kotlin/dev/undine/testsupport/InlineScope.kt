package dev.undine.testsupport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * 상태 홀더가 띄우는 코루틴을 **부른 자리에서 그대로** 실행하는 테스트 스코프.
 *
 * 홀더의 `launch` 가 즉시 끝나야 "무엇이 불렸는가" 를 대기 없이 대조할 수 있다 — 스레드를 넘기면
 * 테스트가 타이밍에 기대게 되고, 그때부터 실패가 산발적으로 바뀐다.
 *
 * **dispatcher 를 파라미터로 받는다.** 코드 안에서 dispatcher 를 직접 고르지 않게 하는 검사
 * (detekt `InjectDispatcher`)를 지키려는 것이고, 그 빚 목록은 `config/detekt/README.md` 에 있다.
 * 새 스펙이 그 빚을 늘리지 않도록 여기 한 자리에서만 기본값을 정한다.
 */
fun inlineTestScope(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): CoroutineScope =
    CoroutineScope(dispatcher + Job())
