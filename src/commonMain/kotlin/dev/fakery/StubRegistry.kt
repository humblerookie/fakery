package dev.fakery

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
}

/**
 * A single stub paired with an **atomic** call counter for sequence advancement.
 *
 * [callCount] uses `atomicfu` so concurrent requests cannot skip a step or
 * observe the same index twice.
 */
internal class StatefulEntry(val definition: StubDefinition) {

    private val callCount = atomic(0)

    /** Tests whether this entry matches [incoming]. Pure — does not advance the counter. */
    fun matches(incoming: IncomingRequest): Boolean {
        val req = definition.request

        val methodMatch  = req.method.equals(incoming.method, ignoreCase = true)
        val pathMatch    = req.path == incoming.path
        val headersMatch = req.headers.all { (name, value) -> incoming.headers[name] == value }
        val bodyMatch    = req.body == null || req.body == incoming.body

        return methodMatch && pathMatch && headersMatch && bodyMatch
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
