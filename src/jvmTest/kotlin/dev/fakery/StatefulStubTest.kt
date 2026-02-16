package dev.fakery

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatefulStubTest {

    private lateinit var server: FakeryServer

    @BeforeTest
    fun setUp() { server = fakery(stubs = emptyList()).also { it.start() } }

    @AfterTest
    fun tearDown() = server.stop()

    // ── Sequence advancement ──────────────────────────────────────────────────

    @Test
    fun `sequence returns responses in order`() {
        server.addStub(StubDefinition(
            request  = StubRequest(path = "/job"),
            sequence = listOf(
                StubResponse(status = 202, body = buildJsonObject { put("status", "pending") }),
                StubResponse(status = 202, body = buildJsonObject { put("status", "processing") }),
                StubResponse(status = 200, body = buildJsonObject { put("status", "complete") }),
            ),
        ))

        assertEquals(202, get("/job").first)
        assertEquals(202, get("/job").first)
        assertEquals(200, get("/job").first)
    }

    @Test
    fun `last response is repeated after sequence is exhausted`() {
        server.addStub(StubDefinition(
            request  = StubRequest(path = "/flaky"),
            sequence = listOf(
                StubResponse(status = 503),
                StubResponse(status = 200),
            ),
        ))

        assertEquals(503, get("/flaky").first)   // call 1
        assertEquals(200, get("/flaky").first)   // call 2
        assertEquals(200, get("/flaky").first)   // call 3 — last repeated
        assertEquals(200, get("/flaky").first)   // call 4 — still last
    }

    @Test
    fun `error then success retry scenario`() {
        server.addStub(StubDefinition(
            request  = StubRequest(method = "POST", path = "/submit"),
            sequence = listOf(
                StubResponse(status = 500, body = buildJsonObject { put("error", "internal") }),
                StubResponse(status = 500, body = buildJsonObject { put("error", "internal") }),
                StubResponse(status = 201, body = buildJsonObject { put("id", 42)             }),
            ),
        ))

        val (s1, _) = post("/submit")
        val (s2, _) = post("/submit")
        val (s3, b3) = post("/submit")

        assertEquals(500, s1)
        assertEquals(500, s2)
        assertEquals(201, s3)
        assertEquals(true, b3.contains("42"))
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset rewinds sequence counters without removing stubs`() {
        server.addStub(StubDefinition(
            request  = StubRequest(path = "/counter"),
            sequence = listOf(
                StubResponse(status = 200, body = buildJsonObject { put("call", 1) }),
                StubResponse(status = 200, body = buildJsonObject { put("call", 2) }),
            ),
        ))

        // First run
        assertEquals(200, get("/counter").first)   // call 1
        assertEquals(200, get("/counter").first)   // call 2

        server.reset()

        // Second run — back to first response
        val (_, body) = get("/counter")
        assertEquals(true, body.contains("\"call\":1"))
    }

    // ── Backward compatibility ────────────────────────────────────────────────

    @Test
    fun `single response stubs still work`() {
        server.addStub(StubDefinition(
            request  = StubRequest(path = "/static"),
            response = StubResponse(status = 200),
        ))

        assertEquals(200, get("/static").first)
        assertEquals(200, get("/static").first)
        assertEquals(200, get("/static").first)
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `both response and sequence throws at construction`() {
        assertFailsWith<IllegalArgumentException> {
            StubDefinition(
                request  = StubRequest(path = "/bad"),
                response = StubResponse(status = 200),
                sequence = listOf(StubResponse(status = 201)),
            )
        }
    }

    @Test
    fun `neither response nor sequence throws at construction`() {
        assertFailsWith<IllegalArgumentException> {
            StubDefinition(
                request  = StubRequest(path = "/bad"),
                response = null,
                sequence = null,
            )
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    @Test
    fun `sequence is parsed from JSON`() {
        val stub = parseStub("""
            {
              "request": { "path": "/seq" },
              "sequence": [
                { "status": 503 },
                { "status": 200 }
              ]
            }
        """.trimIndent())

        assertEquals(2,   stub.responses.size)
        assertEquals(503, stub.responses[0].status)
        assertEquals(200, stub.responses[1].status)
    }

    @Test
    fun `sequence stubs served from JSON`() {
        val json = """
            {
              "request":  { "path": "/from-json" },
              "sequence": [
                { "status": 429, "body": { "error": "rate limited" } },
                { "status": 200, "body": { "data": "ok" } }
              ]
            }
        """.trimIndent()
        server.addStub(parseStub(json))

        assertEquals(429, get("/from-json").first)
        assertEquals(200, get("/from-json").first)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun get(path: String): Pair<Int, String>  = request("GET",  path)
    private fun post(path: String): Pair<Int, String> = request("POST", path)

    private fun request(method: String, path: String): Pair<Int, String> {
        @Suppress("DEPRECATION")
        val conn = URL("${server.baseUrl}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3_000
        conn.readTimeout    = 3_000
        val status       = conn.responseCode
        val responseBody = runCatching {
            (if (status < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
        }.getOrDefault("")
        conn.disconnect()
        return status to responseBody
    }
}
