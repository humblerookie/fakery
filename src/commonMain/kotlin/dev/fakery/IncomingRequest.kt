package dev.fakery

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.JsonElement

/**
 * Parsed snapshot of an inbound HTTP request used for stub matching.
 * Created once per request to avoid repeated suspend calls.
 */
internal data class IncomingRequest(
    val method: String,
    val path: String,
    val headers: io.ktor.http.Headers,
    val body: JsonElement?,
) {
    companion object {
        suspend fun from(call: ApplicationCall, stubs: List<StubDefinition>): IncomingRequest {
            val needsBody = stubs.any { it.request.body != null }
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

        /** Overload used by [StatefulEntry] where the stub list isn't available. */
        suspend fun from(call: ApplicationCall): IncomingRequest = from(call, emptyList())
    }
}
