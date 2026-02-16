package dev.anvith.fakery

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.client.request.request
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatefulStubTest {

    private lateinit var server: FakeryServer
    private lateinit var client: HttpClient

    @BeforeTest
    fun setUp() {
        client = HttpClient { expectSuccess = false }
        server = fakery(stubs = emptyList()).also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.stop()
    }

    // ── Sequence advancement ──────────────────────────────────────────────────

    @Test
    fun `sequence returns responses in order`() = runTest {
        server.addStub(StubDefinition(
            request   = StubRequest(path = "/job"),
            responses = listOf(
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
    fun `last response is repeated after sequence is exhausted`() = runTest {
        server.addStub(StubDefinition(
            request   = StubRequest(path = "/flaky"),
            responses = listOf(
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
    fun `error then success retry scenario`() = runTest {
        server.addStub(StubDefinition(
            request   = StubRequest(method = "POST", path = "/submit"),
            responses = listOf(
                StubResponse(status = 500, body = buildJsonObject { put("error", "internal") }),
                StubResponse(status = 500, body = buildJsonObject { put("error", "internal") }),
                StubResponse(status = 201, body = buildJsonObject { put("id", 42) }),
            ),
        ))

        val (s1, _)   = post("/submit")
        val (s2, _)   = post("/submit")
        val (s3, b3)  = post("/submit")

        assertEquals(500, s1)
        assertEquals(500, s2)
        assertEquals(201, s3)
        assertEquals(true, b3.contains("42"))
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset rewinds sequence counters without removing stubs`() = runTest {
        server.addStub(StubDefinition(
            request   = StubRequest(path = "/counter"),
            responses = listOf(
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
    fun `single response stubs still work`() = runTest {
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
                request   = StubRequest(path = "/bad"),
                response  = StubResponse(status = 200),
                responses = listOf(StubResponse(status = 201)),
            )
        }
    }

    @Test
    fun `neither response nor sequence throws at construction`() {
        assertFailsWith<IllegalArgumentException> {
            StubDefinition(
                request   = StubRequest(path = "/bad"),
                response  = null,
                responses = null,
            )
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    @Test
    fun `sequence is parsed from JSON`() {
        val stub = parseStub("""
            {
              "request": { "path": "/seq" },
              "responses": [
                { "status": 503 },
                { "status": 200 }
              ]
            }
        """.trimIndent())

        assertEquals(2,   stub.resolvedResponses.size)
        assertEquals(503, stub.resolvedResponses[0].status)
        assertEquals(200, stub.resolvedResponses[1].status)
    }

    @Test
    fun `sequence stubs served from JSON`() = runTest {
        val json = """
            {
              "request":  { "path": "/from-json" },
              "responses": [
                { "status": 429, "body": { "error": "rate limited" } },
                { "status": 200, "body": { "data": "ok" } }
              ]
            }
        """.trimIndent()
        server.addStub(parseStub(json))

        assertEquals(429, get("/from-json").first)
        assertEquals(200, get("/from-json").first)
    }

    // ── Concurrency ───────────────────────────────────────────────────────────

    @Test
    fun `concurrent requests each advance the counter exactly once`() = runTest {
        server.addStub(StubDefinition(
            request   = StubRequest(path = "/concurrent"),
            responses = (1..20).map { i ->
                StubResponse(status = 200, body = buildJsonObject { put("step", i) })
            },
        ))

        // Fire 20 coroutines concurrently and collect the step values returned
        val bodies: List<String> = coroutineScope {
            (1..20).map { async { get("/concurrent").second } }.awaitAll()
        }

        // Every step 1..20 must appear exactly once — no two coroutines got the same index
        val seen = bodies.mapNotNull { body ->
            Regex(""""step":(\d+)""").find(body)?.groupValues?.get(1)?.toInt()
        }.sorted()

        assertEquals((1..20).toList(), seen)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun get(path: String): Pair<Int, String>  = request("GET",  path)
    private suspend fun post(path: String): Pair<Int, String> = request("POST", path)

    private suspend fun request(method: String, path: String): Pair<Int, String> {
        val response = client.request("${server.baseUrl}$path") {
            this.method = HttpMethod.parse(method)
        }
        return response.status.value to response.bodyAsText()
    }
}
