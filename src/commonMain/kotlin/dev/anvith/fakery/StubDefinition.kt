package dev.anvith.fakery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single stub — pairs an inbound [StubRequest] matcher with one or more [StubResponse]s.
 *
 * ### Single response (default, backward-compatible)
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
 * ### Stateful sequence
 * Provide a `responses` array to return different responses on successive calls.
 * Once the sequence is exhausted the **last response is repeated** indefinitely.
 * ```json
 * {
 *   "request": { "method": "GET", "path": "/job/123" },
 *   "responses": [
 *     { "status": 202, "body": { "status": "pending"    } },
 *     { "status": 202, "body": { "status": "processing" } },
 *     { "status": 200, "body": { "status": "complete"   } }
 *   ]
 * }
 * ```
 * Call 1 → `202 pending`, Call 2 → `202 processing`, Call 3+ → `200 complete`.
 *
 * **Constraint:** exactly one of [response] or [responses] must be non-null.
 *
 * Stub files may contain a single object or a JSON array of objects.
 * Matching is **first-match-wins** in registration order.
 */
@Serializable
data class StubDefinition(
    val request: StubRequest,

    /** Single canned response. Mutually exclusive with [responses]. */
    val response: StubResponse? = null,

    /**
     * Ordered list of responses for stateful scenarios.
     * Mutually exclusive with [response].
     * The last element is repeated indefinitely once the list is exhausted.
     */
    val responses: List<StubResponse>? = null,
) {
    init {
        val hasResponse  = response != null
        val hasResponses = !responses.isNullOrEmpty()
        require(hasResponse xor hasResponses) {
            val identifier = request.path ?: request.pathPattern ?: "<any>"
            "StubDefinition for path '$identifier' must define exactly one of " +
            "'response' or 'responses' (not both, not neither)."
        }
    }

    /** Resolved response list — always non-empty after construction. */
    internal val resolvedResponses: List<StubResponse>
        get() = responses ?: listOf(response!!)
}

/**
 * Describes which inbound request a stub should match.
 *
 * All non-null fields must match for the stub to be selected.
 * Fields omitted from the JSON take their default values.
 *
 * ### Path matching
 * Use **either** [path] (exact) or [pathPattern] (regex), not both.
 * When both are omitted the stub matches any path — useful as a catch-all.
 *
 * ### Query parameter matching
 * Provide a [queryParams] map; only the declared keys are checked — extra
 * query parameters on the incoming request are silently ignored.
 *
 * ### Body matching
 * Use [body] for exact JSON equality, or [bodyContains] for a substring check
 * on the raw request body. Both can be set; both must match.
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
     *
     * Mutually exclusive with [pathPattern]. When both are `null`, any path matches.
     */
    val path: String? = null,

    /**
     * Regex pattern to match the request path against.
     *
     * The pattern is anchored — it must match the **entire** path, not just a substring.
     * Use `.*` for substring-style matching: `"/users/.*"` matches `/users/123` and `/users/abc`.
     *
     * Example: `"^/users/\\d+$"` matches `/users/1`, `/users/42`, but not `/users/abc`.
     *
     * Mutually exclusive with [path]. When both are `null`, any path matches.
     */
    val pathPattern: String? = null,

    /**
     * Header subset to match. Only keys declared here are checked;
     * extra headers on the incoming request are ignored.
     *
     * Header *names* are matched case-insensitively; *values* are exact (case-sensitive).
     *
     * Example: `{ "Authorization": "Bearer token" }` requires the request to
     * carry that exact Authorization header value.
     */
    val headers: Map<String, String> = emptyMap(),

    /**
     * Query parameter subset to match. Only keys declared here are checked;
     * extra query parameters on the incoming request are silently ignored.
     *
     * Both key and value comparisons are case-sensitive.
     * Values should be provided URL-decoded (Fakery decodes incoming params before comparison).
     *
     * Example: `{ "status": "active", "page": "1" }` requires both params to be present
     * with exactly those values.
     */
    val queryParams: Map<String, String>? = null,

    /**
     * JSON body to match for exact equality. When `null` (the default), JSON equality
     * is not checked.
     *
     * When present, the incoming body is parsed as JSON and compared structurally.
     * Use this for POST/PUT stubs that should only fire for a specific payload.
     *
     * Compatible with [bodyContains] — both conditions must be satisfied when set.
     */
    val body: JsonElement? = null,

    /**
     * Substring to search for in the raw request body. When `null` (the default),
     * the body is not checked for containment.
     *
     * Useful for partial body matching when you only care that a specific field or
     * value is present, without constraining the entire payload.
     *
     * Example: `"\"role\":\"admin\""` matches any body containing that JSON fragment.
     *
     * Compatible with [body] — both conditions must be satisfied when set.
     */
    val bodyContains: String? = null,
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

    /**
     * Milliseconds to wait before sending this response.
     *
     * Use this to simulate slow endpoints and verify your client's timeout behaviour.
     * When `null` (the default), the response is sent immediately.
     *
     * Example: `"delayMs": 500` adds a 500 ms delay before the response is written.
     */
    val delayMs: Long? = null,
)
