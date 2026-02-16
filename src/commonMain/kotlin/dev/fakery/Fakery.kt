package dev.fakery

/** Platform-specific server factory wired via expect/actual. */
internal expect fun createFakeryServer(port: Int, stubs: MutableList<StubDefinition>): FakeryServer

// ── Entry points ─────────────────────────────────────────────────────────────

/**
 * Creates a [FakeryServer] from a list of already-parsed [StubDefinition]s.
 *
 * Use this overload when you build stubs programmatically:
 * ```kotlin
 * val stubs = listOf(
 *     StubDefinition(
 *         request  = StubRequest(method = "GET", path = "/users"),
 *         response = StubResponse(status = 200),
 *     )
 * )
 * val server = fakery(stubs = stubs)
 * server.start()
 * ```
 *
 * @param port Port to listen on. `0` (default) lets the OS assign a free port — recommended
 *             for tests to avoid port conflicts when running in parallel.
 * @param stubs Pre-parsed stub definitions.
 * @return A [FakeryServer] ready to [FakeryServer.start].
 */
fun fakery(port: Int = 0, stubs: List<StubDefinition>): FakeryServer =
    createFakeryServer(port, stubs.toMutableList())

/**
 * Creates a [FakeryServer] by parsing stubs from a JSON string.
 *
 * The string may be either a **single stub object**:
 * ```json
 * { "request": { "method": "GET", "path": "/ping" }, "response": { "status": 200 } }
 * ```
 * or a **JSON array** of stubs:
 * ```json
 * [
 *   { "request": { "path": "/a" }, "response": { "status": 200 } },
 *   { "request": { "path": "/b" }, "response": { "status": 404 } }
 * ]
 * ```
 *
 * Example:
 * ```kotlin
 * val server = fakery(json = File("stubs/users.json").readText())
 * server.start()
 * // point your HTTP client at server.baseUrl
 * server.stop()
 * ```
 *
 * @param port Port to listen on. `0` = OS picks a free port (recommended).
 * @param json JSON string — single stub object or array.
 * @return A [FakeryServer] ready to [FakeryServer.start].
 * @throws kotlinx.serialization.SerializationException if the JSON is malformed.
 */
fun fakery(port: Int = 0, json: String): FakeryServer {
    val stubs = json.trim().let { s ->
        if (s.startsWith("[")) parseStubs(s) else listOf(parseStub(s))
    }
    return fakery(port, stubs)
}

/**
 * Creates a [FakeryServer] by loading all `.json` stub files from [directoryPath].
 *
 * Each file may contain a single [StubDefinition] object **or** a JSON array of them.
 * Files are loaded in filesystem order; later stubs do not override earlier ones —
 * matching is first-match-wins.
 *
 * ```kotlin
 * val server = fakeryFromDirectory("src/test/resources/stubs/")
 * server.start()
 * ```
 *
 * @param port Port to listen on. `0` = OS picks a free port.
 * @param directoryPath Path to a directory containing `.json` stub files.
 * @return A [FakeryServer] ready to [FakeryServer.start].
 */
fun fakeryFromDirectory(port: Int = 0, directoryPath: String): FakeryServer =
    fakery(port, loadStubsFromDirectory(directoryPath))

/**
 * Creates a [FakeryServer] from a single `.json` stub file.
 *
 * The file may contain a single [StubDefinition] object **or** a JSON array of them.
 *
 * ```kotlin
 * val server = fakeryFromFile("src/test/resources/stubs/users.json")
 * server.start()
 * ```
 *
 * @param port Port to listen on. `0` = OS picks a free port.
 * @param filePath Path to a `.json` file.
 * @return A [FakeryServer] ready to [FakeryServer.start].
 */
fun fakeryFromFile(port: Int = 0, filePath: String): FakeryServer =
    fakery(port, loadStubsFromFile(filePath))
