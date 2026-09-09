package dev.undine.infrastructure.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

private const val LOOPBACK = "127.0.0.1"
private const val ANY_FREE_PORT = 0
private const val NO_BACKLOG = 0
private const val NO_DELAY_SECONDS = 0

/** 제품 기본값은 10분이라 그대로 두면 테스트가 10분 걸린다. 짧게 주입해 같은 계약을 본다. */
private const val TEST_REQUEST_TIMEOUT_MILLIS = 300L

/** 타임아웃이 사라졌을 때 테스트가 매달리지 않도록 두는 상한. 여유는 크게 준다. */
private const val HANG_GUARD_MILLIS = 8_000L

private const val HTTP_OK = 200
private const val HTTP_MOVED = 302
private const val HTTP_NOT_FOUND = 404

private const val NO_RESPONSE_BODY = -1L

private const val JSON_BODY = """{"tag_name": "v2.0.0"}"""
private const val ASSET_BODY = "설치 파일 내용"

private typealias Route = (HttpExchange) -> Unit

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}

private fun HttpExchange.redirectTo(location: String) {
    responseHeaders.add("Location", location)
    sendResponseHeaders(HTTP_MOVED, NO_RESPONSE_BODY)
    responseBody.close()
}

/**
 * **붙기는 하는데 응답하지 않는** 서버. 연결만 받아 두고 아무것도 쓰지 않는다.
 *
 * 이것이 연결 타임아웃으로는 못 막는 상황이다 — TCP 는 성립했으므로 `connectTimeout` 이 걸리지
 * 않는다. 요청 타임아웃이 없으면 호출이 영영 돌아오지 않는다.
 */
private fun withSilentServer(block: (URI) -> Unit) {
    val server = ServerSocket(ANY_FREE_PORT, NO_BACKLOG, InetAddress.getByName(LOOPBACK))
    val accepting = Thread {
        runCatching { while (true) server.accept() }
    }.apply { isDaemon = true; start() }
    try {
        block(URI.create("http://$LOOPBACK:${server.localPort}"))
    } finally {
        runCatching { server.close() }
        accepting.interrupt()
    }
}

/**
 * 로컬 임시 서버를 띄워 [block] 에 기준 URI 를 넘긴다.
 *
 * **실제 github.com 을 부르지 않는다** (결정 D3) — JDK 내장 `com.sun.net.httpserver` 라 새 의존성도
 * 없다. fake `ReleaseHttpClient` 는 인터페이스만 대역할 뿐 이 어댑터의 실제 HTTP 동작을 검증하지
 * 못한다 (결정 D23).
 */
private fun withServer(routes: Map<String, Route>, block: (URI) -> Unit) {
    val server = HttpServer.create(InetSocketAddress(LOOPBACK, ANY_FREE_PORT), NO_BACKLOG)
    routes.forEach { (path, route) ->
        server.createContext(path) { exchange ->
            try {
                route(exchange)
            } finally {
                exchange.close()
            }
        }
    }
    server.start()
    try {
        block(URI.create("http://$LOOPBACK:${server.address.port}"))
    } finally {
        server.stop(NO_DELAY_SECONDS)
    }
}

/** 아무도 듣지 않는 포트. 서버를 잠깐 띄웠다 내려 확실히 비어 있는 번호를 얻는다. */
/**
 * 아무도 듣지 않는 포트.
 *
 * `HttpServer` 를 만들었다 멈추는 방식을 쓰지 않는다 — 그러면 포트가 **열린 채 응답만 없는** 상태가
 * 되어 TCP 는 붙고(연결 타임아웃이 안 걸린다) 응답은 오지 않는다. 접속 거부를 재현하려면 소켓을
 * 실제로 닫아야 한다.
 */
private fun closedPortUri(): URI {
    val port = ServerSocket(ANY_FREE_PORT, NO_BACKLOG, InetAddress.getByName(LOOPBACK)).use { it.localPort }
    return URI.create("http://$LOOPBACK:$port")
}

/**
 * `JdkReleaseHttpClient` 의 실제 조회 · 다운로드 · 리다이렉트.
 *
 * 리다이렉트 회귀가 이 스펙의 핵심이다 — 따라가지 않으면 자산 자리에 302 본문이 저장되고, 그
 * 파일은 "체크섬 불일치" 로만 보여 진짜 원인이 드러나지 않는다.
 */
class JdkReleaseHttpClientSpec : FunSpec({

    test("본문을 상태 코드와 함께 그대로 읽는다") {
        withServer(mapOf("/latest" to { exchange -> exchange.respond(HTTP_OK, JSON_BODY) })) { base ->
            val response = runBlocking { JdkReleaseHttpClient().getText(base.resolve("/latest")) }

            response.statusCode shouldBe HTTP_OK
            response.body shouldBe JSON_BODY
        }
    }

    test("404 는 예외가 아니라 상태 코드로 올라온다 — 릴리즈 없음을 실패로 접지 않는다") {
        withServer(mapOf("/latest" to { exchange -> exchange.respond(HTTP_NOT_FOUND, "Not Found") })) { base ->
            val response = runBlocking { JdkReleaseHttpClient().getText(base.resolve("/latest")) }

            response.statusCode shouldBe HTTP_NOT_FOUND
        }
    }

    test("붙기만 하고 응답하지 않는 서버에 매달리지 않는다") {
        withSilentServer { base ->
            val client = JdkReleaseHttpClient(requestTimeout = Duration.ofMillis(TEST_REQUEST_TIMEOUT_MILLIS))

            val failure = runCatching {
                runBlocking {
                    // 요청 타임아웃이 없으면 호출이 돌아오지 않는다. 그때 먼저 터지는 것은
                    // TimeoutCancellationException 이고, IOException 이 아니라 이 단언이 실패한다 —
                    // 테스트가 매달리는 대신 사유를 밝히며 빨간불이 된다.
                    withTimeout(HANG_GUARD_MILLIS) { client.getText(base.resolve("/latest")) }
                }
            }.exceptionOrNull()

            failure.shouldBeInstanceOf<IOException>()
        }
    }

    test("붙지 못하면 IOException 으로 올린다 — 조용한 빈 응답이 없다") {
        val unreachable = closedPortUri().resolve("/latest")

        val failure = runCatching { runBlocking { JdkReleaseHttpClient().getText(unreachable) } }

        failure.exceptionOrNull().shouldBeInstanceOf<IOException>()
    }

    test("자산 본문을 대상 파일에 그대로 쓴다") {
        val target = tempdir().toPath().resolve("undine.deb")
        withServer(mapOf("/asset" to { exchange -> exchange.respond(HTTP_OK, ASSET_BODY) })) { base ->
            val status = runBlocking { JdkReleaseHttpClient().downloadTo(base.resolve("/asset"), target) }

            status shouldBe HTTP_OK
            Files.readString(target) shouldBe ASSET_BODY
        }
    }

    test("자산 다운로드가 리다이렉트를 따라간다 — 302 본문을 저장하지 않는다") {
        val target = tempdir().toPath().resolve("undine.deb")
        val routes = mapOf<String, Route>(
            "/asset" to { exchange -> exchange.redirectTo("/storage") },
            "/storage" to { exchange -> exchange.respond(HTTP_OK, ASSET_BODY) },
        )
        withServer(routes) { base ->
            val status = runBlocking { JdkReleaseHttpClient().downloadTo(base.resolve("/asset"), target) }

            status shouldBe HTTP_OK
            Files.readString(target) shouldBe ASSET_BODY
        }
    }

    test("조회도 리다이렉트를 따라간다") {
        val routes = mapOf<String, Route>(
            "/latest" to { exchange -> exchange.redirectTo("/moved") },
            "/moved" to { exchange -> exchange.respond(HTTP_OK, JSON_BODY) },
        )
        withServer(routes) { base ->
            val response = runBlocking { JdkReleaseHttpClient().getText(base.resolve("/latest")) }

            response.statusCode shouldBe HTTP_OK
            response.body shouldBe JSON_BODY
        }
    }
})
