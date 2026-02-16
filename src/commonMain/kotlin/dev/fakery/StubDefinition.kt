package dev.fakery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single stub — pairs an inbound [StubRequest] matcher with a canned [StubResponse].
 *
 * ### JSON format
 * ```json
 * {
 *   "request": {
 *     "method":  "GET",
 *     "path":    "/users/123",
 *     "headers": { "Authorization": "Bearer token" },
 *     "body":    { "filter": "active" }
 *   },
 *   "response": {
 *     "status":  200,
 *     "headers": { "X-Request-Id": "abc" },
 *     "body":    { "id": 123, "name": "Alice" }
 *   }
 * }
 * ```
 *
 * Stubs files may contain a single object or a JSON array of objects.
 * Matching is **first-match-wins** in registration order.
 */
@Serializable
data class StubDefinition(
    val request: StubRequest,
    val response: StubResponse,
)

/**
 * Describes which inbound request a stub should match.
 *
 * All non-null fields must match for the stub to be selected.
 * Fields omitted from the JSON take their default values.
 */
@Serializable
data class StubRequest(
    /**
     * HTTP method to match, case-insensitive.
     *
     * Common values: `"GET"`, `"POST"`, `"PUT"`, `"DELETE"`, `"PATCH"`.
     * Defaults to `"GET"` when omitted.
     */
    val method: String = "GET",

    /**
     * Exact request path to match. The query string is stripped before comparison.
     *
     * Example: `"/users/123"` matches `GET /users/123?foo=bar`.
     */
    val path: String,

    /**
     * Header subset to match. Only keys declared here are checked;
     * extra headers on the incoming request are ignored.
     *
     * Comparison is case-insensitive on values.
     *
     * Example: `{ "Authorization": "Bearer token" }` requires the request to
     * carry that exact Authorization header value.
     */
    val headers: Map<String, String> = emptyMap(),

    /**
     * JSON body to match. When `null` (the default), the request body is not checked.
     * When present, the incoming body is parsed as JSON and compared for equality.
     *
     * Use this for POST/PUT stubs that should only fire for a specific payload.
     */
    val body: JsonElement? = null,
)

/**
 * Describes the HTTP response Fakery sends when a [StubRequest] is matched.
 */
@Serializable
data class StubResponse(
    /**
     * HTTP status code. Defaults to `200`.
     *
     * Use standard codes: `200 OK`, `201 Created`, `204 No Content`,
     * `400 Bad Request`, `401 Unauthorized`, `404 Not Found`, `500 Internal Server Error`, etc.
     */
    val status: Int = 200,

    /**
     * Headers to include in the response. Merged with Fakery's default headers.
     *
     * The `Content-Type` header is derived from [body] automatically unless overridden here.
     */
    val headers: Map<String, String> = emptyMap(),

    /**
     * Response body as a JSON value.
     *
     * - `null` (default) → empty response body
     * - `JsonObject`     → serialized to a JSON object string
     * - `JsonArray`      → serialized to a JSON array string
     * - `JsonPrimitive`  → serialized to its JSON representation (string, number, boolean)
     *
     * The `Content-Type` defaults to `application/json; charset=utf-8` unless
     * overridden via [headers].
     */
    val body: JsonElement? = null,
)
