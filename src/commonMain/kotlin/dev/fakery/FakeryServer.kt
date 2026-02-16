package dev.fakery

/**
 * A running fake HTTP server backed by Ktor CIO.
 *
 * Create instances via the [fakery], [fakeryFromFile], or [fakeryFromDirectory] entry points.
 *
 * ### Typical test lifecycle
 * ```kotlin
 * class MyApiTest {
 *     private lateinit var server: FakeryServer
 *
 *     @BeforeTest
 *     fun setUp() {
 *         server = fakery(json = """
 *             [
 *               { "request": { "path": "/users" },   "response": { "status": 200, "body": [] } },
 *               { "request": { "path": "/users/1" }, "response": { "status": 200, "body": {"id":1} } }
 *             ]
 *         """)
 *         server.start()
 *     }
 *
 *     @AfterTest
 *     fun tearDown() = server.stop()
 *
 *     @Test
 *     fun `fetch users`() = runTest {
 *         val users = myApiClient(server.baseUrl).getUsers()
 *         assertEquals(0, users.size)
 *     }
 * }
 * ```
 *
 * ### Stub matching
 * On each inbound request, Fakery walks the stub list in registration order and returns the
 * **first** stub where **all** of the following match:
 * 1. `method` — case-insensitive (defaults to `GET` when omitted in JSON)
 * 2. `path`   — exact match, query string is stripped before comparison
 * 3. `headers`— every key/value in the stub must be present in the request (subset match)
 * 4. `body`   — skipped when `null`; compared as parsed JSON when present
 *
 * If no stub matches, the server returns `404` with a JSON error body.
 */
interface FakeryServer : AutoCloseable {

    /**
     * The base URL of the server, e.g. `"http://localhost:52341"`.
     * Use this as the `baseUrl` for your HTTP client under test.
     */
    val baseUrl: String

    /**
     * The port the server is (or will be) listening on.
     * Useful when [port] was specified as `0` and you need to know the assigned port.
     */
    val port: Int

    /**
     * Starts the server. Returns once the server is ready to accept requests.
     *
     * Call this in your test `setUp` / `@BeforeTest` method.
     * Pair with [stop] to avoid port leaks between tests.
     */
    fun start()

    /**
     * Stops the server and releases all resources.
     *
     * Safe to call multiple times. Call this in your `tearDown` / `@AfterTest` method.
     */
    fun stop()

    /**
     * Adds a stub at runtime. Safe to call after [start].
     *
     * New stubs are appended to the end of the list; use this to add
     * test-specific overrides after the server has already started.
     *
     * ```kotlin
     * server.addStub(StubDefinition(
     *     request  = StubRequest(path = "/feature-flag"),
     *     response = StubResponse(status = 200, body = JsonPrimitive(true)),
     * ))
     * ```
     */
    fun addStub(stub: StubDefinition)

    /**
     * Removes all registered stubs.
     *
     * Useful between test cases when sharing a single server instance:
     * ```kotlin
     * @AfterTest fun reset() = server.clearStubs()
     * ```
     * Any request arriving after this call (before new stubs are added) will receive a `404`.
     */
    fun clearStubs()

    /**
     * Alias for [stop]. Enables use with Kotlin's `use {}` block:
     * ```kotlin
     * fakery(json = stubs).use { server ->
     *     server.start()
     *     // test...
     * } // stop() called automatically
     * ```
     */
    override fun close() = stop()
}
