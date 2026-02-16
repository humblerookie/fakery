package dev.fakery

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Ktor application module shared by all platforms.
 * Intercepts every request, finds a matching stub, and responds accordingly.
 */
internal fun Application.fakeryModule(stubs: List<StubDefinition>) {
    intercept(ApplicationCallPipeline.Call) {
        val stub = matchStub(call, stubs)

        if (stub != null) {
            val response = stub.response

            // Add stub-defined headers
            response.headers.forEach { (key, value) -> call.response.header(key, value) }

            val body        = response.body?.toJsonString() ?: ""
            val contentType = response.headers["Content-Type"] ?: "application/json; charset=utf-8"

            call.respondText(
                text        = body,
                contentType = ContentType.parse(contentType),
                status      = HttpStatusCode.fromValue(response.status),
            )
        } else {
            val method = call.request.httpMethod.value
            val path   = call.request.local.uri.substringBefore("?")

            call.respondText(
                text   = """{"error":"No stub for $method $path"}""",
                status = HttpStatusCode.NotFound,
            )
        }
    }
}

private fun JsonElement.toJsonString(): String = Json.encodeToString(JsonElement.serializer(), this)
