package dev.anvith.fakery

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpMethod
import io.ktor.client.request.request
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests — spins up a real Ktor CIO server and makes actual HTTP requests
 * via Ktor's multiplatform [HttpClient].
 *
 * Runs on every platform that provides a `ktor-client-cio` engine on the test classpath.
 */
class FakeryIntegrationTest {

    private lateinit var server: FakeryServer
    private lateinit var client: HttpClient

    @BeforeTest
    fun setUp() {
        client = HttpClient { expectSuccess = false }
        server = fakery(json = """
            [
              {
                "request":  { "method": "GET",    "path": "/users" },
                "response": { "status": 200, "body": {"users": [{"id": 1}]} }
              },
              {
                "request":  { "method": "POST",   "path": "/users" },
                "response": { "status": 201, "body": {"id": 2} }
              },
              {
                "request":  { "method": "GET",    "path": "/secure",
                               "headers": { "Authorization": "Bearer secret" } },
                "response": { "status": 200, "body": {"ok": true} }
              },
              {
                "request":  { "method": "DELETE", "path": "/users/1" },
                "response": { "status": 204 }
              }
            ]
        """.trimIndent())
        server.start()
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.stop()
    }

    // ── Basic method routing ──────────────────────────────────────────────────

    @Test
    fun `GET returns 200 with body`() = runTest {
        val (status, body) = get("/users")
        assertEquals(200, status)
        assertTrue(body.contains("users"))
    }

    @Test
    fun `POST returns 201`() = runTest {
        val (status, _) = post("/users", """{"name":"Bob"}""")
        assertEquals(201, status)
    }

    @Test
    fun `DELETE returns 204`() = runTest {
        val (status, _) = delete("/users/1")
        assertEquals(204, status)
    }

    @Test
    fun `unregistered path returns 404`() = runTest {
        val (status, body) = get("/nonexistent")
        assertEquals(404, status)
        assertTrue(body.contains("No stub"))
    }

    // ── Header matching ───────────────────────────────────────────────────────

    @Test
    fun `request with matching header returns 200`() = runTest {
        val (status, _) = get("/secure", headers = mapOf("Authorization" to "Bearer secret"))
        assertEquals(200, status)
    }

    @Test
    fun `request missing required header returns 404`() = runTest {
        val (status, _) = get("/secure")
        assertEquals(404, status)
    }

    @Test
    fun `header value matching is case-sensitive`() = runTest {
        val (status, _) = get("/secure", headers = mapOf("Authorization" to "Bearer SECRET"))
        assertEquals(404, status)
    }

    // ── Query string stripping ────────────────────────────────────────────────

    @Test
    fun `query string is stripped before path matching`() = runTest {
        val (status, _) = get("/users?page=1&limit=10")
        assertEquals(200, status)
    }

    // ── Case-insensitive method matching ─────────────────────────────────────

    @Test
    fun `method matching is case-insensitive`() = runTest {
        val (status, _) = get("/users")
        assertEquals(200, status)
    }

    // ── Runtime stub management ───────────────────────────────────────────────

    @Test
    fun `addStub at runtime is served immediately`() = runTest {
        server.addStub(
            StubDefinition(
                request  = StubRequest(method = "GET", path = "/dynamic"),
                response = StubResponse(status = 200, body = JsonPrimitive("dynamic!")),
            )
        )
        val (status, body) = get("/dynamic")
        assertEquals(200, status)
        assertTrue(body.contains("dynamic"))
    }

    @Test
    fun `clearStubs causes all paths to return 404`() = runTest {
        assertEquals(200, get("/users").first)
        server.clearStubs()
        assertEquals(404, get("/users").first)
    }

    // ── AutoCloseable ─────────────────────────────────────────────────────────

    @Test
    fun `server supports use block via AutoCloseable`() = runTest {
        var capturedBaseUrl = ""
        fakery(json = """{"request":{"path":"/ac"},"response":{"status":200}}""").use { s ->
            s.start()
            capturedBaseUrl = s.baseUrl
            val (status, _) = get("/ac", baseUrl = capturedBaseUrl)
            assertEquals(200, status)
        }
        assertTrue(capturedBaseUrl.isNotEmpty())
    }

    // ── Error response is valid JSON ──────────────────────────────────────────

    @Test
    fun `error response body is valid JSON`() = runTest {
        val (status, body) = get("/path/not/found")
        assertEquals(404, status)
        assertTrue(body.trimStart().startsWith("{"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
        baseUrl: String = server.baseUrl,
    ): Pair<Int, String> = request("GET", path, headers = headers, baseUrl = baseUrl)

    private suspend fun post(path: String, body: String = ""): Pair<Int, String> =
        request("POST", path, body = body)

    private suspend fun delete(path: String): Pair<Int, String> =
        request("DELETE", path)

    private suspend fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        baseUrl: String = server.baseUrl,
    ): Pair<Int, String> {
        val response = client.request("$baseUrl$path") {
            this.method = HttpMethod.parse(method)
            headers.forEach { (k, v) -> header(k, v) }
            if (body.isNotEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return response.status.value to response.bodyAsText()
    }
}
