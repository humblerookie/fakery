package dev.fakery

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ktor application module shared by all platforms.
 *
 * Accepts a [getStubs] lambda instead of a direct list reference so each platform
 * can return a safe snapshot (avoiding ConcurrentModificationException on JVM and
 * data races on native).
 */
internal fun Application.fakeryModule(getStubs: () -> List<StubDefinition>) {
    intercept(ApplicationCallPipeline.Call) {
        // Snapshot at request time — safe regardless of concurrent addStub/clearStubs calls.
        val stubs = getStubs()
        val stub  = matchStub(call, stubs)

        if (stub != null) {
            val response = stub.response
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

            // Use buildJsonObject so method/path values are properly escaped.
            val errorBody = buildJsonObject { put("error", "No stub for $method $path") }.toString()

            call.respondText(
                text   = errorBody,
                status = HttpStatusCode.NotFound,
            )
        }
    }
}

private fun JsonElement.toJsonString(): String = Json.encodeToString(JsonElement.serializer(), this)
