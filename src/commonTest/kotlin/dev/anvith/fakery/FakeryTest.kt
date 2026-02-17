package dev.anvith.fakery

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

        assertEquals("GET",       stub.request.method)
        assertEquals("/users/123", stub.request.path)
        assertEquals(200,          stub.response!!.status)
        assertNotNull(stub.response!!.body)
    }

    @Test
    fun `parse stub array from JSON array`() {
        val json = """
            [
              { "request": { "path": "/a" }, "response": { "status": 200 } },
              { "request": { "path": "/b" }, "response": { "status": 404 } }
            ]
        """.trimIndent()

        val stubs = parseStubs(json)

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
        assertEquals(200, stub.response!!.status)
    }

    @Test
    fun `body is null when absent`() {
        val stub = parseStub("""{"request":{"path":"/ping"},"response":{"status":204}}""")
        assertNull(stub.request.body)
        assertNull(stub.response!!.body)
    }

    @Test
    fun `headers are parsed correctly`() {
        val json = """
            {
              "request":  { "path": "/secure", "headers": { "Authorization": "Bearer token" } },
              "response": { "status": 200,     "headers": { "X-Custom": "value" }            }
            }
        """.trimIndent()

        val stub = parseStub(json)

        assertEquals("Bearer token", stub.request.headers["Authorization"])
        assertEquals("value",        stub.response!!.headers["X-Custom"])
    }

    @Test
    fun `fakery entry point accepts JSON string`() {
        val server = fakery(json = """{"request":{"path":"/health"},"response":{"status":200}}""")
        assertNotNull(server)
    }

    @Test
    fun `fakery entry point accepts stub list`() {
        val stubs = listOf(
            StubDefinition(
                request  = StubRequest(path = "/ping"),
                response = StubResponse(status = 200),
            )
        )
        val server = fakery(stubs = stubs)
        assertNotNull(server)
    }

    @Test
    fun `single file containing a JSON array is parsed as multiple stubs`() {
        val json = """
            [
              { "request": { "method": "POST", "path": "/login"  }, "response": { "status": 401 } },
              { "request": { "method": "GET",  "path": "/logout" }, "response": { "status": 200 } }
            ]
        """.trimIndent()

        val stubs = parseStubs(json)

        assertEquals(2,       stubs.size)
        assertEquals("POST",  stubs[0].request.method)
        assertEquals("GET",   stubs[1].request.method)
        assertEquals(401,     stubs[0].response!!.status)
        assertEquals(200,     stubs[1].response!!.status)
    }
}
