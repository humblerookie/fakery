package dev.fakery

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ── JSON parsing ─────────────────────────────────────────────────────────────

class StubParsingTest {

    @Test
    fun `parse single stub from JSON object`() {
        val json = """
            {
              "request":  { "method": "GET", "path": "/users/123" },
              "response": { "status": 200, "body": {"id": 123, "name": "Alice"} }
            }
        """.trimIndent()

        val stub = parseStub(json)

        assertEquals("GET",        stub.request.method)
        assertEquals("/users/123", stub.request.path)
        assertEquals(200,          stub.response.status)
        assertNotNull(stub.response.body)
    }

    @Test
    fun `parse stub array from JSON array`() {
        val stubs = parseStubs("""
            [
              { "request": { "path": "/a" }, "response": { "status": 200 } },
              { "request": { "path": "/b" }, "response": { "status": 404 } }
            ]
        """.trimIndent())

        assertEquals(2,    stubs.size)
        assertEquals("/a", stubs[0].request.path)
        assertEquals("/b", stubs[1].request.path)
    }

    @Test
    fun `method defaults to GET when absent`() {
        val stub = parseStub("""{"request":{"path":"/ping"},"response":{"status":200}}""")
        assertEquals("GET", stub.request.method)
    }

    @Test
    fun `status defaults to 200 when absent`() {
        val stub = parseStub("""{"request":{"path":"/ping"},"response":{}}""")
        assertEquals(200, stub.response.status)
    }

    @Test
    fun `body is null when absent`() {
        val stub = parseStub("""{"request":{"path":"/ping"},"response":{"status":204}}""")
        assertNull(stub.request.body)
        assertNull(stub.response.body)
    }

    @Test
    fun `headers are parsed correctly`() {
        val stub = parseStub("""
            {
              "request":  { "path": "/secure", "headers": { "Authorization": "Bearer token" } },
              "response": { "status": 200,     "headers": { "X-Custom": "value" }            }
            }
        """.trimIndent())

        assertEquals("Bearer token", stub.request.headers["Authorization"])
        assertEquals("value",        stub.response.headers["X-Custom"])
    }

    @Test
    fun `fakery() entry point accepts JSON string`() {
        val server = fakery(json = """{"request":{"path":"/health"},"response":{"status":200}}""")
        assertNotNull(server)
    }

    @Test
    fun `fakery() entry point accepts stub list`() {
        val server = fakery(stubs = listOf(
            StubDefinition(
                request  = StubRequest(path = "/ping"),
                response = StubResponse(status = 200),
            )
        ))
        assertNotNull(server)
    }

    @Test
    fun `single JSON array parsed as multiple stubs`() {
        val stubs = parseStubs("""
            [
              { "request": { "method": "POST", "path": "/login"  }, "response": { "status": 401 } },
              { "request": { "method": "GET",  "path": "/logout" }, "response": { "status": 200 } }
            ]
        """.trimIndent())

        assertEquals(2,      stubs.size)
        assertEquals("POST", stubs[0].request.method)
        assertEquals("GET",  stubs[1].request.method)
        assertEquals(401,    stubs[0].response.status)
    }
}

// ── Stub model ───────────────────────────────────────────────────────────────

class StubDefinitionTest {

    @Test
    fun `StubDefinition can be constructed programmatically`() {
        val stub = StubDefinition(
            request  = StubRequest(
                method  = "POST",
                path    = "/users",
                headers = mapOf("Content-Type" to "application/json"),
                body    = buildJsonObject { put("name", "Alice") },
            ),
            response = StubResponse(
                status  = 201,
                headers = mapOf("Location" to "/users/1"),
                body    = buildJsonObject { put("id", 1) },
            ),
        )

        assertEquals("POST",             stub.request.method)
        assertEquals("/users",           stub.request.path)
        assertEquals("application/json", stub.request.headers["Content-Type"])
        assertNotNull(stub.request.body)
        assertEquals(201,                stub.response.status)
        assertEquals("/users/1",         stub.response.headers["Location"])
        assertNotNull(stub.response.body)
    }

    @Test
    fun `primitive JSON body is supported`() {
        val stub = StubDefinition(
            request  = StubRequest(path = "/flag"),
            response = StubResponse(body = JsonPrimitive(true)),
        )
        assertEquals(JsonPrimitive(true), stub.response.body)
    }
}
