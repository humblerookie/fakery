package dev.fakery

/** Platform-specific server factory. */
internal expect fun createFakeryServer(port: Int, stubs: MutableList<StubDefinition>): FakeryServer

// ── Entry points ─────────────────────────────────────────────────────────────

/**
 * Creates a [FakeryServer] from a list of already-parsed [StubDefinition]s.
 *
 * @param port `0` = OS picks a free port (recommended for tests).
 */
fun fakery(port: Int = 0, stubs: List<StubDefinition>): FakeryServer =
    createFakeryServer(port, stubs.toMutableList())

/**
 * Creates a [FakeryServer] by parsing stubs from a JSON string.
 *
 * The string can be either:
 * - a single stub object: `{ "request": {...}, "response": {...} }`
 * - a JSON array of stubs: `[{ ... }, { ... }]`
 *
 * @param port `0` = OS picks a free port.
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
 * @param port `0` = OS picks a free port.
 */
fun fakeryFromDirectory(port: Int = 0, directoryPath: String): FakeryServer =
    fakery(port, loadStubsFromDirectory(directoryPath))

/**
 * Creates a [FakeryServer] from a single stub file.
 *
 * @param port `0` = OS picks a free port.
 */
fun fakeryFromFile(port: Int = 0, filePath: String): FakeryServer =
    fakery(port, loadStubsFromFile(filePath))
