package dev.undine.infrastructure.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 연결 대기 상한(초). 오프라인에서 확인이 영원히 매달리지 않게 한다. */
private const val CONNECT_TIMEOUT_SECONDS = 10L

/**
 * 응답 전체를 기다리는 한계.
 *
 * **연결 타임아웃만으로는 부족하다** — 상대가 TCP 는 받아 놓고 응답을 주지 않으면 연결은 성립했으므로
 * `connectTimeout` 이 걸리지 않고, 요청은 영원히 돌아오지 않는다. 그러면 "네트워크 실패는 조용히
 * 넘기고 다음 주기에 재시도한다"(결정 D6)가 성립할 수 없다 — 돌아오지 않으니 다음 주기가 없다.
 *
 * 자산 다운로드까지 덮으므로 조회보다 넉넉히 잡는다.
 */
private const val REQUEST_TIMEOUT_MINUTES = 10L

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)
private val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(REQUEST_TIMEOUT_MINUTES)

/** GitHub API 는 User-Agent 없는 요청을 거절한다. 앱 이름 하나만 보낸다 — 식별 정보를 싣지 않는다. */
private const val USER_AGENT = "Undine"

private const val HEADER_USER_AGENT = "User-Agent"
private const val HEADER_ACCEPT = "Accept"
private const val GITHUB_JSON_MEDIA_TYPE = "application/vnd.github+json"

/** 응답 상태와 본문. */
class ReleaseHttpResponse(val statusCode: Int, val body: String)

/**
 * 릴리즈 조회·다운로드의 HTTP 경계.
 *
 * 인터페이스로 두는 이유는 `FileManagerLauncher` 와 같다 — **테스트가 실제 github.com 을 타지 않게**
 * 하려는 것이다 (결정 D3). 네트워크는 이 레포가 겪은 "테스트가 환경을 빌려 쓰는" 문제 중 가장 흔들린다.
 */
interface ReleaseHttpClient {

    /** 본문을 문자열로 읽는다. 네트워크 실패는 `IOException` 으로 올린다 — 조용한 빈 응답이 없다. */
    suspend fun getText(uri: URI): ReleaseHttpResponse

    /**
     * 본문을 [target] 에 쓰고 상태 코드를 돌려준다.
     *
     * 200 이 아니면 [target] 의 내용은 의미가 없다 — 호출부가 그 파일을 지운다.
     */
    suspend fun downloadTo(uri: URI, target: Path): Int
}

/**
 * JDK 내장 `java.net.http.HttpClient` 구현. **새 HTTP 의존성을 들이지 않는다** (결정 D19) —
 * 하루 한 번 GET 두 번에 라이브러리를 더할 이유가 없다.
 *
 * 이 모듈은 `app/build.gradle.kts` 의 `nativeDistributions.modules(...)` 에 들어 있어야 한다.
 * 빠지면 **빌드가 아니라 패키징된 앱의 실행 시점에** 실패한다.
 */
class JdkReleaseHttpClient(
    private val client: HttpClient = defaultReleaseHttpClient(),
) : ReleaseHttpClient {

    override suspend fun getText(uri: URI): ReleaseHttpResponse = withContext(Dispatchers.IO) {
        runInterruptible {
            val response = client.send(requestFor(uri), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            ReleaseHttpResponse(statusCode = response.statusCode(), body = response.body())
        }
    }

    override suspend fun downloadTo(uri: URI, target: Path): Int = withContext(Dispatchers.IO) {
        runInterruptible {
            client.send(requestFor(uri), HttpResponse.BodyHandlers.ofFile(target)).statusCode()
        }
    }

    private fun requestFor(uri: URI): HttpRequest = HttpRequest.newBuilder(uri)
        .timeout(REQUEST_TIMEOUT)
        .header(HEADER_USER_AGENT, USER_AGENT)
        .header(HEADER_ACCEPT, GITHUB_JSON_MEDIA_TYPE)
        .GET()
        .build()
}

/**
 * 리다이렉트를 따라가는 기본 클라이언트.
 *
 * `followRedirects(NORMAL)` 이 없으면 **릴리즈 자산 다운로드가 302 본문을 받아 저장한다** — 그
 * 파일은 체크섬이 맞지 않아 "무결성 검증 실패" 로 보이고, 진짜 원인은 드러나지 않는다.
 */
internal fun defaultReleaseHttpClient(): HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(CONNECT_TIMEOUT)
    .executor(releaseHttpExecutor())
    .build()

/**
 * 클라이언트가 쓰는 **데몬 스레드** 실행자.
 *
 * 이 앱은 창을 닫으면 프로세스가 내려간다 (`closeAndExit`). 실행자가 사용자 스레드를 들고 있으면
 * **창은 사라졌는데 프로세스가 남는다** — 사용자에게는 앱이 안 꺼진 것으로 보인다.
 * 데몬으로 두면 종료를 붙잡지 않으므로 별도의 닫기 배선을 앱 전체에 끌고 다니지 않아도 된다.
 */
private fun releaseHttpExecutor(): Executor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "undine-update-http").apply { isDaemon = true }
}
