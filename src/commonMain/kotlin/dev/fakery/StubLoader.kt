package dev.fakery

import kotlinx.serialization.json.Json

// Lenient parser — tolerates unknown keys, missing fields, etc.
internal val FakeryJson = Json {
    ignoreUnknownKeys = true
    isLenient         = true
    coerceInputValues = true
}

/**
 * Parses a single JSON stub string into a [StubDefinition].
 *
 * ```kotlin
 * val stub = parseStub("""{"request":{"path":"/ping"},"response":{"status":200}}""")
 * ```
 */
fun parseStub(json: String): StubDefinition =
    FakeryJson.decodeFromString(StubDefinition.serializer(), json)

/**
 * Parses a JSON array of stubs.
 *
 * ```kotlin
 * val stubs = parseStubs("""[{"request":{"path":"/a"},"response":{"status":200}}]""")
 * ```
 */
fun parseStubs(json: String): List<StubDefinition> =
    FakeryJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(StubDefinition.serializer()), json)

/**
 * Platform-specific: read all `.json` stub files from [directoryPath] and return parsed stubs.
 *
 * Each file may contain either a single [StubDefinition] object or a JSON array of them.
 */
expect fun loadStubsFromDirectory(directoryPath: String): List<StubDefinition>

/**
 * Platform-specific: read a single `.json` stub file and return the parsed stub(s).
 *
 * The file may contain either a single [StubDefinition] object or a JSON array of them.
 */
expect fun loadStubsFromFile(filePath: String): List<StubDefinition>
