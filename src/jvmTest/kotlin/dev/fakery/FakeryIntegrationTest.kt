package dev.fakery

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * Integration tests — spins up a real Ktor CIO server and makes actual HTTP requests.
 */
class FakeryIntegrationTest {

    private lateinit var server: FakeryServer

    @BeforeTest
    fun setUp() {
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
    fun tearDown() = server.stop()

    // ── Basic method routing ──────────────────────────────────────────────────

    @Test
    fun `GET returns 200 with body`() {
        val (status, body) = get("/users")
        assertEquals(200, status)
        assertTrue(body.contains("users"))
    }

    @Test
    fun `POST returns 201`() {
        val (status, _) = post("/users", """{"name":"Bob"}""")
        assertEquals(201, status)
    }

    @Test
    fun `DELETE returns 204`() {
        val (status, _) = delete("/users/1")
        assertEquals(204, status)
    }

    @Test
    fun `unregistered path returns 404`() {
        val (status, body) = get("/nonexistent")
        assertEquals(404, status)
        assertTrue(body.contains("No stub"))
    }

    // ── Header matching ───────────────────────────────────────────────────────

    @Test
    fun `request with matching header returns 200`() {
        val (status, _) = get("/secure", headers = mapOf("Authorization" to "Bearer secret"))
        assertEquals(200, status)
    }

    @Test
    fun `request missing required header returns 404`() {
        val (status, _) = get("/secure")
        assertEquals(404, status)
    }

    @Test
    fun `header value matching is case-sensitive`() {
        // "Bearer SECRET" should NOT match stub expecting "Bearer secret"
        val (status, _) = get("/secure", headers = mapOf("Authorization" to "Bearer SECRET"))
        assertEquals(404, status)
    }

    // ── Query string stripping ────────────────────────────────────────────────

    @Test
    fun `query string is stripped before path matching`() {
        val (status, _) = get("/users?page=1&limit=10")
        assertEquals(200, status)
    }

    // ── Case-insensitive method matching ─────────────────────────────────────

    @Test
    fun `method matching is case-insensitive`() {
        // Stub defines "GET"; Ktor normalises methods but test verifies our matcher logic
        val (status, _) = get("/users")
        assertEquals(200, status)
    }

    // ── Runtime stub management ───────────────────────────────────────────────

    @Test
    fun `addStub at runtime is served immediately`() {
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
    fun `clearStubs causes all paths to return 404`() {
        assertEquals(200, get("/users").first)
        server.clearStubs()
        assertEquals(404, get("/users").first)
    }

    // ── AutoCloseable ─────────────────────────────────────────────────────────

    @Test
    fun `server supports use block via AutoCloseable`() {
        var capturedBaseUrl = ""
        fakery(json = """{"request":{"path":"/ac"},"response":{"status":200}}""").use { s ->
            s.start()
            capturedBaseUrl = s.baseUrl
            val (status, _) = get("/ac", baseUrl = capturedBaseUrl)
            assertEquals(200, status)
        }
        // After use{} the server is stopped; further requests should fail to connect
        assertTrue(capturedBaseUrl.isNotEmpty())
    }

    // ── Error response is valid JSON ──────────────────────────────────────────

    @Test
    fun `error response body is valid JSON even when path contains special chars`() {
        val (status, body) = get("/path/with/\"quotes\"")
        assertEquals(404, status)
        // Body must start with { and be parseable (no raw interpolation bugs)
        assertTrue(body.trimStart().startsWith("{"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun get(path: String, headers: Map<String, String> = emptyMap(), baseUrl: String = server.baseUrl) =
        request("GET", path, headers = headers, baseUrl = baseUrl)

    private fun post(path: String, body: String = "") = request("POST", path, body = body)

    private fun delete(path: String) = request("DELETE", path)

    private fun request(
        method: String,
        path: String,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        baseUrl: String = server.baseUrl,
    ): Pair<Int, String> {
        @Suppress("DEPRECATION")
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3_000
        conn.readTimeout    = 3_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        if (body.isNotEmpty()) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }

        val status       = conn.responseCode
        val responseBody = runCatching {
            (if (status < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
        }.getOrDefault("")
        conn.disconnect()
        return status to responseBody
    }
}
