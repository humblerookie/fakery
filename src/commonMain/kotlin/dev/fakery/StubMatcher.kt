package dev.fakery

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.JsonElement

/**
 * Finds the first stub that matches an incoming Ktor [ApplicationCall].
 *
 * Matching rules (all must pass):
 * 1. **method**  — case-insensitive (HTTP spec allows any casing)
 * 2. **path**    — exact match; query string is stripped before comparison
 * 3. **headers** — stub headers are a required subset of incoming headers;
 *                  header *names* are matched case-insensitively (per RFC 7230),
 *                  but header *values* are matched exactly (case-sensitive)
 * 4. **body**    — only checked when the stub defines a body; must equal incoming JSON exactly
 */
internal suspend fun matchStub(call: ApplicationCall, stubs: List<StubDefinition>): StubDefinition? {
    val incomingMethod  = call.request.httpMethod.value
    val incomingPath    = call.request.local.uri.substringBefore("?")
    val incomingHeaders = call.request.headers

    // Only parse the body once, and only when at least one stub requires body matching.
    val needsBody = stubs.any { it.request.body != null }
    val incomingBody: JsonElement? = if (needsBody) {
        // Use the same lenient parser as stubs to avoid false mismatches.
        runCatching { FakeryJson.parseToJsonElement(call.receiveText()) }.getOrNull()
    } else null

    return stubs.firstOrNull { stub ->
        val req = stub.request

        // 1. Method — case-insensitive per HTTP spec
        val methodMatch = req.method.equals(incomingMethod, ignoreCase = true)

        // 2. Path — exact match (query string already stripped above)
        val pathMatch = req.path == incomingPath

        // 3. Headers — names are case-insensitive (RFC 7230), values are case-sensitive
        val headersMatch = req.headers.all { (name, expectedValue) ->
            incomingHeaders[name] == expectedValue
        }

        // 4. Body — skipped when stub body is null
        val bodyMatch = req.body == null || req.body == incomingBody

        methodMatch && pathMatch && headersMatch && bodyMatch
    }
}
