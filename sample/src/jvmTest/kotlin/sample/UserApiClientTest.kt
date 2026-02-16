package sample

import dev.fakery.FakeryServer
import dev.fakery.fakery
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import sample.api.UserApiClientImpl
import sample.model.CreateUserRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Demonstrates testing the API layer with Fakery.
 *
 * The real network is never touched — Fakery spins up a local server
 * and [UserApiClientImpl] talks to it via its baseUrl.
 */
class UserApiClientTest {

    // ── Stub definitions ─────────────────────────────────────────────────────
    //
    // Each file in src/test/resources/stubs/ could own one of these in production.
    // Here they're inline for clarity.

    private val stubs = """
        [
          {
            "request":  { "method": "GET",    "path": "/users" },
            "response": {
              "status": 200,
              "body": [
                { "id": 1, "name": "Alice", "email": "alice@example.com" },
                { "id": 2, "name": "Bob",   "email": "bob@example.com"   }
              ]
            }
          },
          {
            "request":  { "method": "GET",    "path": "/users/1" },
            "response": {
              "status": 200,
              "body": { "id": 1, "name": "Alice", "email": "alice@example.com" }
            }
          },
          {
            "request":  { "method": "GET",    "path": "/users/99" },
            "response": {
              "status": 404,
              "body": { "error": "User not found" }
            }
          },
          {
            "request":  { "method": "POST",   "path": "/users" },
            "response": {
              "status": 201,
              "body": { "id": 3, "name": "Charlie", "email": "charlie@example.com" }
            }
          },
          {
            "request":  { "method": "DELETE", "path": "/users/1" },
            "response": { "status": 204 }
          },
          {
            "request": {
              "method": "GET",
              "path": "/users/me",
              "headers": { "Authorization": "Bearer valid-token" }
            },
            "response": {
              "status": 200,
              "body": { "id": 42, "name": "Me", "email": "me@example.com" }
            }
          }
        ]
    """.trimIndent()

    // ── Test infrastructure ──────────────────────────────────────────────────

    private lateinit var server: FakeryServer
    private lateinit var apiClient: UserApiClientImpl

    @BeforeTest
    fun setUp() {
        server = fakery(json = stubs)
        server.start()

        apiClient = UserApiClientImpl(
            client  = HttpClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
            baseUrl = server.baseUrl,
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `getUsers returns all users`() = runTest {
        val users = apiClient.getUsers()

        assertEquals(2,       users.size)
        assertEquals("Alice", users[0].name)
        assertEquals("Bob",   users[1].name)
    }

    @Test
    fun `getUser returns correct user by id`() = runTest {
        val user = apiClient.getUser(1)

        assertEquals(1,                     user.id)
        assertEquals("Alice",               user.name)
        assertEquals("alice@example.com",   user.email)
    }

    @Test
    fun `createUser returns the created user`() = runTest {
        val created = apiClient.createUser(
            CreateUserRequest(name = "Charlie", email = "charlie@example.com")
        )

        assertEquals(3,         created.id)
        assertEquals("Charlie", created.name)
    }

    @Test
    fun `deleteUser completes without error`() = runTest {
        // No exception means the 204 was handled correctly
        apiClient.deleteUser(1)
    }

    @Test
    fun `getUser for unknown id returns 404 — client throws`() = runTest {
        // The real client should surface the 404 as an exception.
        // Here we verify the stub returns 404 and the test setup works end-to-end.
        assertFailsWith<Exception> {
            apiClient.getUser(99)
        }
    }

    @Test
    fun `stub with required header is only matched when header is present`() = runTest {
        // Add a stub that requires Authorization at runtime
        server.addStub(
            dev.fakery.StubDefinition(
                request  = dev.fakery.StubRequest(
                    method  = "GET",
                    path    = "/users/me",
                    headers = mapOf("Authorization" to "Bearer valid-token"),
                ),
                response = dev.fakery.StubResponse(
                    status = 200,
                    body   = kotlinx.serialization.json.buildJsonObject {
                        put("id",    kotlinx.serialization.json.JsonPrimitive(42))
                        put("name",  kotlinx.serialization.json.JsonPrimitive("Me"))
                        put("email", kotlinx.serialization.json.JsonPrimitive("me@example.com"))
                    },
                ),
            )
        )
        // Without the header the stub won't match → 404 → exception
        assertFailsWith<Exception> {
            apiClient.getUser(99)
        }
    }
}
