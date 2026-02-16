package dev.fakery

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
    val headers: Headers,
    val body: JsonElement?,
) {
    companion object {
        /**
         * Parses the incoming [call] into an [IncomingRequest].
         *
         * @param needsBody When `true`, the request body is read and parsed as JSON.
         *                  Pass `stubs.any { it.request.body != null }` to avoid
         *                  reading the body stream when no stub needs body matching.
         */
        suspend fun from(call: ApplicationCall, needsBody: Boolean): IncomingRequest {
            val body: JsonElement? = if (needsBody) {
                runCatching { FakeryJson.parseToJsonElement(call.receiveText()) }.getOrNull()
            } else null

            return IncomingRequest(
                method  = call.request.httpMethod.value,
                path    = call.request.local.uri.substringBefore("?"),
                headers = call.request.headers,
                body    = body,
            )
        }
    }
}
