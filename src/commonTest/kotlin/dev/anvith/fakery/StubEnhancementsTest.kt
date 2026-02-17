package dev.anvith.fakery
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource
/**
 * Tests for the five stub enhancement features:
 * 1. [StubResponse.delayMs] — artificial response delay
 * 2. [StubRequest.pathPattern] — regex path matching
 * 3. [FakeryServer.getCallCount] / [FakeryServer.verifyCallCount] — call-count assertions
 * 4. [StubRequest.queryParams] — query-parameter matching
 * 5. [StubRequest.bodyContains] — substring body matching
 */
class StubEnhancementsTest {
    private lateinit var server: FakeryServer
    private lateinit var client: HttpClient
    @BeforeTest
    fun setUp() {
        client = HttpClient { expectSuccess = false }
    }
    @AfterTest
    fun tearDown() {
        client.close()
        if (::server.isInitialized) server.stop()
    }
    // ─────────────────────────────────────────────────────────────────────────
    // 1. delayMs
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `delayMs - response arrives after specified delay`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "GET", "path": "/slow" },
              "response": { "status": 200, "body": "ok", "delayMs": 200 }
            }]
        """.trimIndent())
        server.start()
        val mark     = TimeSource.Monotonic.markNow()
        val response = client.get("${server.baseUrl}/slow")
        val elapsed  = mark.elapsedNow().inWholeMilliseconds
        assertEquals(200, response.status.value)
        assertTrue(elapsed >= 200L, "Expected delay >= 200 ms, was $elapsed ms")
    }
    @Test
    fun `delayMs - null means no artificial delay`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "GET", "path": "/fast" },
              "response": { "status": 200 }
            }]
        """.trimIndent())
        server.start()
        // Just verify the stub works; no timing assertion needed
        val response = client.get("${server.baseUrl}/fast")
        assertEquals(200, response.status.value)
    }
    // ─────────────────────────────────────────────────────────────────────────
    // 2. pathPattern (regex)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `pathPattern - matches numeric user id`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "GET", "pathPattern": "/users/\\d+" },
              "response": { "status": 200, "body": { "matched": true } }
            }]
        """.trimIndent())
        server.start()
        assertEquals(200, client.get("${server.baseUrl}/users/1").status.value)
        assertEquals(200, client.get("${server.baseUrl}/users/999").status.value)
    }
    @Test
    fun `pathPattern - does not match non-numeric segment`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "GET", "pathPattern": "/users/\\d+" },
              "response": { "status": 200 }
            }]
        """.trimIndent())
        server.start()
        assertEquals(404, client.get("${server.baseUrl}/users/abc").status.value)
    }
    @Test
    fun `pathPattern - wildcard suffix matches any sub-path`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "GET", "pathPattern": "/api/.*" },
              "response": { "status": 200 }
            }]
        """.trimIndent())
        server.start()
        assertEquals(200, client.get("${server.baseUrl}/api/v1/users").status.value)
        assertEquals(200, client.get("${server.baseUrl}/api/v2/orders/42").status.value)
        assertEquals(404, client.get("${server.baseUrl}/other/path").status.value)
    }
    @Test
    fun `pathPattern - exact path stub still works alongside pathPattern stub`() = runTest {
        server = fakery(json = """
            [
              {
                "request":  { "method": "GET", "path": "/users/me" },
                "response": { "status": 200, "body": { "source": "exact" } }
              },
              {
                "request":  { "method": "GET", "pathPattern": "/users/\\d+" },
                "response": { "status": 200, "body": { "source": "pattern" } }
              }
            ]
        """.trimIndent())
        server.start()
        // Exact stub wins (first-match)
        val meBody = client.get("${server.baseUrl}/users/me").bodyAsText()
        assertTrue(meBody.contains("exact"))
        // Falls through to pattern stub
        val numBody = client.get("${server.baseUrl}/users/42").bodyAsText()
        assertTrue(numBody.contains("pattern"))
    }
    // ─────────────────────────────────────────────────────────────────────────
    // 3. callCount / verifyCallCount
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `getCallCount - returns 0 before any calls`() = runTest {
        server = fakery(json = """
            [{ "request": { "path": "/ping" }, "response": { "status": 200 } }]
        """.trimIndent())
        server.start()
        assertEquals(0, server.getCallCount("GET", "/ping"))
    }
    @Test
    fun `getCallCount - increments with each matched request`() = runTest {
        server = fakery(json = """
            [{ "request": { "path": "/ping" }, "response": { "status": 200 } }]
        """.trimIndent())
        server.start()
        repeat(3) { client.get("${server.baseUrl}/ping") }
        assertEquals(3, server.getCallCount("GET", "/ping"))
    }
    @Test
    fun `getCallCount - returns 0 for unregistered path`() = runTest {
        server = fakery(json = """
            [{ "request": { "path": "/ping" }, "response": { "status": 200 } }]
        """.trimIndent())
        server.start()
        assertEquals(0, server.getCallCount("GET", "/not-registered"))
    }
    @Test
    fun `verifyCallCount - passes when count matches`() = runTest {
        server = fakery(json = """
            [{ "request": { "path": "/login" }, "response": { "status": 200 } }]
        """.trimIndent())
        server.start()
        client.get("${server.baseUrl}/login")
        server.verifyCallCount("GET", "/login", expectedCount = 1) // must not throw
    }
    @Test
    fun `verifyCallCount - throws AssertionError on mismatch`() = runTest {
        server = fakery(json = """
            [{ "request": { "path": "/login" }, "response": { "status": 200 } }]
        """.trimIndent())
        server.start()
        client.get("${server.baseUrl}/login")
        client.get("${server.baseUrl}/login")
        assertFailsWith<AssertionError> {
            server.verifyCallCount("GET", "/login", expectedCount = 1)
        }
    }
    @Test
    fun `getCallCount - reset zeroes the counter`() = runTest {
        server = fakery(json = """
            [{ "request": { "path": "/ping" }, "response": { "status": 200 } }]
        """.trimIndent())
        server.start()
        repeat(5) { client.get("${server.baseUrl}/ping") }
        assertEquals(5, server.getCallCount("GET", "/ping"))
        server.reset()
        assertEquals(0, server.getCallCount("GET", "/ping"))
    }
    // ─────────────────────────────────────────────────────────────────────────
    // 4. queryParams matching
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `queryParams - matches when all specified params are present`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "GET", "path": "/search", "queryParams": { "q": "kotlin", "page": "1" } },
              "response": { "status": 200, "body": { "results": [] } }
            }]
        """.trimIndent())
        server.start()
        assertEquals(200, client.get("${server.baseUrl}/search?q=kotlin&page=1").status.value)
    }
    @Test
    fun `queryParams - extra params on request are ignored`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "GET", "path": "/search", "queryParams": { "q": "kotlin" } },
              "response": { "status": 200 }
            }]
        """.trimIndent())
        server.start()
        // 'page' is extra — stub only cares about 'q'
        assertEquals(200, client.get("${server.baseUrl}/search?q=kotlin&page=99").status.value)
    }
    @Test
    fun `queryParams - does not match when a required param is missing`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "GET", "path": "/search", "queryParams": { "q": "kotlin" } },
              "response": { "status": 200 }
            }]
        """.trimIndent())
        server.start()
        assertEquals(404, client.get("${server.baseUrl}/search").status.value)
        assertEquals(404, client.get("${server.baseUrl}/search?q=java").status.value)
    }
    @Test
    fun `queryParams - combined with exact path matching`() = runTest {
        server = fakery(json = """
            [
              {
                "request":  { "method": "GET", "path": "/items", "queryParams": { "type": "book" } },
                "response": { "status": 200, "body": { "type": "book" } }
              },
              {
                "request":  { "method": "GET", "path": "/items", "queryParams": { "type": "dvd" } },
                "response": { "status": 200, "body": { "type": "dvd" } }
              }
            ]
        """.trimIndent())
        server.start()
        val bookBody = client.get("${server.baseUrl}/items?type=book").bodyAsText()
        assertTrue(bookBody.contains("book"))
        val dvdBody = client.get("${server.baseUrl}/items?type=dvd").bodyAsText()
        assertTrue(dvdBody.contains("dvd"))
    }
    // ─────────────────────────────────────────────────────────────────────────
    // 5. bodyContains
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `bodyContains - matches when body contains the substring`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "POST", "path": "/greet", "bodyContains": "Alice" },
              "response": { "status": 200, "body": { "greeting": "Hello, Alice!" } }
            }]
        """.trimIndent())
        server.start()
        val response = client.post("${server.baseUrl}/greet") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Alice","age":30}""")
        }
        assertEquals(200, response.status.value)
    }
    @Test
    fun `bodyContains - does not match when body lacks the substring`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "POST", "path": "/greet", "bodyContains": "Alice" },
              "response": { "status": 200 }
            }]
        """.trimIndent())
        server.start()
        val response = client.post("${server.baseUrl}/greet") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Bob"}""")
        }
        assertEquals(404, response.status.value)
    }
    @Test
    fun `bodyContains - combines with exact body match`() = runTest {
        // body (exact JSON) AND bodyContains both must hold
        server = fakery(json = """
            [{
              "request": {
                "method": "POST",
                "path": "/data",
                "body": { "role": "admin" },
                "bodyContains": "admin"
              },
              "response": { "status": 200 }
            }]
        """.trimIndent())
        server.start()
        // Matches: exact JSON equals {"role":"admin"} AND contains "admin"
        val ok = client.post("${server.baseUrl}/data") {
            contentType(ContentType.Application.Json)
            setBody("""{"role":"admin"}""")
        }
        assertEquals(200, ok.status.value)
        // Does not match: JSON doesn't equal {"role":"admin"}
        val mismatch = client.post("${server.baseUrl}/data") {
            contentType(ContentType.Application.Json)
            setBody("""{"role":"user","name":"admin"}""")
        }
        assertEquals(404, mismatch.status.value)
    }
    @Test
    fun `bodyContains - works with non-JSON body text`() = runTest {
        server = fakery(json = """
            [{
              "request":  { "method": "POST", "path": "/xml", "bodyContains": "<userId>42</userId>" },
              "response": { "status": 200 }
            }]
        """.trimIndent())
        server.start()
        val response = client.post("${server.baseUrl}/xml") {
            header("Content-Type", "application/xml")
            setBody("<request><userId>42</userId></request>")
        }
        assertEquals(200, response.status.value)
    }
}
