package dev.anvith.fakery

import io.ktor.server.application.ApplicationCall
import kotlinx.atomicfu.atomic

/**
 * Owns the stub list and per-stub call counters for stateful sequences.
 *
 * Platform implementations must ensure [add], [clear], [reset], and [match]
 * are safe to call concurrently from different threads.
 */
internal abstract class StubRegistry {

    /**
     * Parses the request, finds the first matching stub, advances its counter,
     * and returns the next [StubResponse]. Returns `null` if no stub matched.
     */
    abstract suspend fun match(call: ApplicationCall): StubResponse?

    /** Appends a stub. Safe to call after the server has started. */
    abstract fun add(stub: StubDefinition)

    /** Removes all stubs and resets all counters. */
    abstract fun clear()

    /**
     * Resets every stub's sequence counter to zero without removing stubs.
     * Use this between test cases to replay a stateful scenario.
     */
    abstract fun reset()

    /**
     * Returns the number of times a stub matching [method] and [path] (exact) has been called.
     *
     * Searches using first-match-wins semantics — the same ordering used during request matching.
     * Returns `0` if no stub with that method and path is registered.
     *
     * @param method HTTP method string, case-insensitive (e.g. `"GET"`, `"POST"`).
     * @param path   Exact request path (e.g. `"/users/123"`).
     */
    abstract fun getCallCount(method: String, path: String): Int
}

/**
 * A single stub paired with an **atomic** call counter for sequence advancement.
 *
 * [callCount] uses `atomicfu` so concurrent requests cannot skip a step or
 * observe the same index twice.
 */
internal class StatefulEntry(val definition: StubDefinition) {

    private val callCount = atomic(0)

    /** Cached [Regex] compiled once from [StubRequest.pathPattern], if present. */
    private val pathRegex: Regex? = definition.request.pathPattern?.let { Regex(it) }

    /** The total number of times this stub has been matched so far. */
    val currentCallCount: Int get() = callCount.value

    /**
     * Tests whether this entry matches [incoming]. Pure — does not advance the counter.
     *
     * All of the following must hold:
     * 1. **method** — case-insensitive equality
     * 2. **path** — exact match when [StubRequest.path] is set; regex full-match when
     *    [StubRequest.pathPattern] is set; unconstrained when both are `null`
     * 3. **headers** — every stub header key/value must appear in the request (subset)
     * 4. **queryParams** — every stub query key/value must appear in the request (subset)
     * 5. **body** — structural JSON equality when [StubRequest.body] is non-null
     * 6. **bodyContains** — raw body must contain the substring when [StubRequest.bodyContains] is non-null
     */
    fun matches(incoming: IncomingRequest): Boolean {
        val req = definition.request

        val methodMatch = req.method.equals(incoming.method, ignoreCase = true)

        val pathMatch = when {
            pathRegex != null -> pathRegex.matches(incoming.path)
            req.path  != null -> req.path == incoming.path
            else              -> true
        }

        val headersMatch = req.headers.all { (name, value) ->
            incoming.headers[name] == value
        }

        val queryMatch = req.queryParams?.all { (key, value) ->
            incoming.queryParams[key] == value
        } ?: true

        val bodyMatch = req.body == null || req.body == incoming.body

        val bodyContainsMatch = req.bodyContains?.let { needle ->
            incoming.rawBody?.contains(needle) == true
        } ?: true

        return methodMatch && pathMatch && headersMatch && queryMatch && bodyMatch && bodyContainsMatch
    }

    /**
     * Atomically advances the counter and returns the corresponding response.
     * Once the sequence is exhausted the last response is returned indefinitely.
     */
    fun nextResponse(): StubResponse {
        val responses = definition.resolvedResponses
        val index     = minOf(callCount.getAndIncrement(), responses.size - 1)
        return responses[index]
    }

    /** Resets the counter without removing the stub. */
    fun resetCounter() { callCount.value = 0 }
}
