package dev.fakery

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.JsonElement

/**
 * Finds the first stub that matches an incoming [ApplicationCall].
 *
 * Matching rules (all must pass):
 * 1. **method**  — case-insensitive (HTTP spec)
 * 2. **path**    — exact match; query string is stripped before comparison
 * 3. **headers** — stub headers are a required subset; names case-insensitive, values exact
 * 4. **body**    — skipped when `null`; must equal incoming body when present
 *
 * @return The next [StubResponse] from the matched stub (advances sequence counter),
 *         or `null` if no stub matched.
 */
internal suspend fun matchStub(call: ApplicationCall, stubs: List<StatefulEntry>): StubResponse? {
    return stubs.firstOrNull { it.matches(call) }?.nextResponse()
}
