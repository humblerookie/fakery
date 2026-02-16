package dev.anvith.fakery

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ktor application module shared by all platforms.
 * Delegates matching and sequence advancement to [registry].
 */
internal fun Application.fakeryModule(registry: StubRegistry) {
    intercept(ApplicationCallPipeline.Call) {
        val stubResponse = registry.match(call)

        if (stubResponse != null) {
            stubResponse.delayMs?.let { ms -> delay(ms) }

            stubResponse.headers.forEach { (key, value) -> call.response.header(key, value) }

            val body        = stubResponse.body?.toJsonString() ?: ""
            val contentType = stubResponse.headers["Content-Type"] ?: "application/json; charset=utf-8"

            call.respondText(
                text        = body,
                contentType = ContentType.parse(contentType),
                status      = HttpStatusCode.fromValue(stubResponse.status),
            )
        } else {
            val method = call.request.httpMethod.value
            val path   = call.request.local.uri.substringBefore("?")

            call.respondText(
                text   = buildJsonObject { put("error", "No stub for $method $path") }.toString(),
                status = HttpStatusCode.NotFound,
            )
        }
    }
}

private fun JsonElement.toJsonString(): String = Json.encodeToString(JsonElement.serializer(), this)
