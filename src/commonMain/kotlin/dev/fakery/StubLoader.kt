package dev.fakery

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The JSON parser used across Fakery.
 *
 * Configured for lenience so that:
 * - Unknown keys in stub files are silently ignored (forward-compatible stubs)
 * - Missing optional fields fall back to their defaults
 * - Non-strict JSON (trailing commas, comments) is tolerated
 */
internal val FakeryJson = Json {
    ignoreUnknownKeys = true
    isLenient         = true
    coerceInputValues = true
}

/**
 * Parses a single JSON stub string into a [StubDefinition].
 *
 * ```kotlin
 * val stub = parseStub("""
 *   { "request": { "path": "/ping" }, "response": { "status": 200 } }
 * """)
 * val server = fakery(stubs = listOf(stub))
 * ```
 *
 * @throws kotlinx.serialization.SerializationException if the JSON is invalid or missing required fields.
 */
internal fun parseStub(json: String): StubDefinition =
    FakeryJson.decodeFromString(StubDefinition.serializer(), json)

/**
 * Parses a JSON array of stubs into a [List] of [StubDefinition].
 *
 * ```kotlin
 * val stubs = parseStubs("""
 *   [
 *     { "request": { "path": "/a" }, "response": { "status": 200 } },
 *     { "request": { "path": "/b" }, "response": { "status": 404 } }
 *   ]
 * """)
 * val server = fakery(stubs = stubs)
 * ```
 *
 * @throws kotlinx.serialization.SerializationException if the JSON is invalid.
 */
internal fun parseStubs(json: String): List<StubDefinition> =
    FakeryJson.decodeFromString(ListSerializer(StubDefinition.serializer()), json)

/**
 * Loads all `.json` stub files from [directoryPath] and returns the combined list of stubs.
 *
 * Each file may contain either:
 * - a single [StubDefinition] object, or
 * - a JSON array of [StubDefinition] objects.
 *
 * Files are read in filesystem order. Use [fakeryFromDirectory] for the typical entry point.
 *
 * @param directoryPath Absolute or relative path to a directory.
 * @return Flattened list of all stubs found across all `.json` files.
 */
expect fun loadStubsFromDirectory(directoryPath: String): List<StubDefinition>

/**
 * Loads stub(s) from a single `.json` file and returns them as a list.
 *
 * The file may contain either a single [StubDefinition] object or a JSON array.
 * Use [fakeryFromFile] for the typical entry point.
 *
 * @param filePath Absolute or relative path to a `.json` file.
 * @return One or more stubs parsed from the file.
 */
expect fun loadStubsFromFile(filePath: String): List<StubDefinition>
