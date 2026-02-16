package dev.fakery

import io.ktor.server.application.ApplicationCall

/**
 * Owns the list of stubs and their per-stub call counters for stateful sequences.
 *
 * Thread-safety contract: subclasses must ensure [add], [clear], [reset], and [match]
 * are safe to call concurrently from different threads.
 */
internal abstract class StubRegistry {

    /** Finds and advances the matching stub, returning its next [StubResponse]. */
    abstract suspend fun match(call: ApplicationCall): StubResponse?

    /** Appends a stub. Safe to call after the server has started. */
    abstract fun add(stub: StubDefinition)

    /** Removes all stubs and resets all counters. */
    abstract fun clear()

    /**
     * Resets every stub's sequence counter back to zero without removing stubs.
     * Useful for re-running a scenario in the same test without restarting the server.
     */
    abstract fun reset()
}

/**
 * A single stub paired with an atomic call counter for sequence advancement.
 *
 * [nextResponse] is called once per matched request. The counter advances monotonically;
 * once the sequence is exhausted the last response is returned indefinitely.
 */
internal class StatefulEntry(val definition: StubDefinition) {

    private var callCount: Int = 0

    suspend fun matches(call: ApplicationCall): Boolean = matchesStub(call, definition)

    fun nextResponse(): StubResponse {
        val responses = definition.responses
        val index     = minOf(callCount, responses.size - 1)
        callCount++
        return responses[index]
    }

    fun resetCounter() { callCount = 0 }
}

/** Shared matching logic reused by [StatefulEntry]. */
private suspend fun matchesStub(call: ApplicationCall, stub: StubDefinition): Boolean {
    val incoming = IncomingRequest.from(call)
    val req      = stub.request

    val methodMatch  = req.method.equals(incoming.method, ignoreCase = true)
    val pathMatch    = req.path == incoming.path
    val headersMatch = req.headers.all { (name, value) -> incoming.headers[name] == value }
    val bodyMatch    = req.body == null || req.body == incoming.body

    return methodMatch && pathMatch && headersMatch && bodyMatch
}
