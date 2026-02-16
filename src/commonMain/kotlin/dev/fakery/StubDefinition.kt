package dev.fakery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Top-level stub — maps a [StubRequest] to a [StubResponse].
 *
 * JSON format:
 * ```json
 * {
 *   "request":  { "method": "GET", "path": "/users/123", "headers": {}, "body": {} },
 *   "response": { "status": 200,   "headers": {},         "body": {}  }
 * }
 * ```
 */
@Serializable
data class StubDefinition(
    val request: StubRequest,
    val response: StubResponse,
)

@Serializable
data class StubRequest(
    /** HTTP method (case-insensitive). Defaults to GET. */
    val method: String = "GET",

    /** Request path to match, e.g. "/users/123". */
    val path: String,

    /**
     * Headers to match (subset). Only keys declared here are checked;
     * extra headers on the incoming request are ignored.
     */
    val headers: Map<String, String> = emptyMap(),

    /**
     * Body to match. `null` (or absent) means "don't check the body".
     * When present, the incoming body must equal this JSON value exactly.
     */
    val body: JsonElement? = null,
)

@Serializable
data class StubResponse(
    /** HTTP status code to return. */
    val status: Int = 200,

    /** Headers to include in the response. */
    val headers: Map<String, String> = emptyMap(),

    /**
     * Response body. `null` means an empty body.
     * JSON objects/arrays are serialized back to a JSON string before sending.
     */
    val body: JsonElement? = null,
)
