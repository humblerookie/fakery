package dev.fakery

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Finds the first stub that matches an incoming Ktor [ApplicationCall].
 *
 * Matching rules (all must pass):
 * 1. method  — case-insensitive equality
 * 2. path    — exact match (query string stripped from incoming path)
 * 3. headers — stub headers are a required subset of incoming headers
 * 4. body    — only checked when the stub defines a body; must equal incoming body
 */
internal suspend fun matchStub(call: ApplicationCall, stubs: List<StubDefinition>): StubDefinition? {
    val incomingMethod  = call.request.httpMethod.value
    val incomingPath    = call.request.local.uri.substringBefore("?")
    val incomingHeaders = call.request.headers

    // Only parse the body once, and only when at least one stub requires body matching.
    val needsBody = stubs.any { it.request.body != null }
    val incomingBody: JsonElement? = if (needsBody) {
        runCatching { Json.parseToJsonElement(call.receiveText()) }.getOrNull()
    } else null

    return stubs.firstOrNull { stub ->
        val req = stub.request

        val methodMatch = req.method.equals(incomingMethod, ignoreCase = true)
        val pathMatch   = req.path == incomingPath

        val headersMatch = req.headers.all { (key, value) ->
            incomingHeaders[key]?.equals(value, ignoreCase = true) == true
        }

        val bodyMatch = req.body == null || req.body == incomingBody

        methodMatch && pathMatch && headersMatch && bodyMatch
    }
}
