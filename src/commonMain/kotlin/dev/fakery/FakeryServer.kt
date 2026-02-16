package dev.fakery

/**
 * A running fake HTTP server.
 *
 * ```kotlin
 * val server = fakery(json = File("stubs/users.json").readText())
 * server.start()
 * // test against server.baseUrl ...
 * server.stop()
 * ```
 */
interface FakeryServer {
    /** e.g. "http://localhost:52341" */
    val baseUrl: String

    /** The port the server is listening on. */
    val port: Int

    /** Starts the server. Returns once the server is ready to accept requests. */
    fun start()

    /** Stops the server and releases resources. */
    fun stop()

    /** Adds a stub at runtime (safe to call after [start]). */
    fun addStub(stub: StubDefinition)

    /** Removes all registered stubs. */
    fun clearStubs()
}
