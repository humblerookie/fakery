package dev.anvith.fakery

import io.ktor.http.Headers
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.JsonElement

/**
 * Parsed snapshot of an inbound HTTP request.
 *
 * Created **once per request** in [StubRegistry.match] before iterating stubs,
 * so body parsing happens at most once regardless of how many stubs are evaluated.
 */
internal data class IncomingRequest(
    val method: String,
    val path: String,
    /** URL-decoded query parameters. Multi-value keys keep only the first value. */
    val queryParams: Map<String, String>,
    val headers: Headers,
    /** Raw body text, or `null` when no stub required body inspection. */
    val rawBody: String?,
    /** Body parsed as JSON, or `null` when the body was absent or non-JSON. */
    val body: JsonElement?,
) {
    companion object {
        /**
         * Parses the incoming [call] into an [IncomingRequest].
         *
         * @param needsBody When `true`, the request body is read and stored in
         *                  [rawBody] (and parsed as JSON into [body]).
         *                  Pass `stubs.any { it.request.body != null || it.request.bodyContains != null }`
         *                  to avoid reading the body stream when no stub needs body matching.
         */
        suspend fun from(call: ApplicationCall, needsBody: Boolean): IncomingRequest {
            val rawBody: String? = if (needsBody) {
                runCatching { call.receiveText() }.getOrNull()
            } else null

            val parsedBody: JsonElement? = rawBody?.let {
                runCatching { FakeryJson.parseToJsonElement(it) }.getOrNull()
            }

            val queryParams: Map<String, String> = call.request.queryParameters
                .entries()
                .associate { (key, values) -> key to (values.firstOrNull() ?: "") }

            return IncomingRequest(
                method      = call.request.httpMethod.value,
                path        = call.request.local.uri.substringBefore("?"),
                queryParams = queryParams,
                headers     = call.request.headers,
                rawBody     = rawBody,
                body        = parsedBody,
            )
        }
    }
}
