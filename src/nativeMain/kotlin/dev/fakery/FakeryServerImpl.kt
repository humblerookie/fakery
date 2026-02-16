package dev.fakery

import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.runBlocking

internal actual fun createFakeryServer(port: Int, stubs: MutableList<StubDefinition>): FakeryServer =
    NativeFakeryServer(port, stubs)

internal class NativeFakeryServer(
    private val initialPort: Int,
    stubs: List<StubDefinition>,
) : FakeryServer {

    private val registry = NativeStubRegistry(stubs)

    private var _port: Int = 0
    override val port: Int get() = _port
    override val baseUrl: String get() = "http://localhost:$_port"

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun start() {
        server = embeddedServer(CIO, port = initialPort) {
            fakeryModule(registry)
        }.start(wait = false)
        _port = runBlocking { server!!.engine.resolvedConnectors().first().port }
    }

    override fun stop() {
        server?.engine?.stop(gracePeriodMillis = 100L, timeoutMillis = 500L)
        server = null
    }

    override fun addStub(stub: StubDefinition) = registry.add(stub)
    override fun clearStubs()                  = registry.clear()
    override fun reset()                       = registry.reset()
}

/**
 * Native [StubRegistry].
 *
 * - `atomicfu.AtomicRef` + [update] — CAS loop on `add` to eliminate the TOCTOU race
 *   that `@Volatile` alone cannot prevent
 * - Body parsed once per request before iterating stubs
 * - Per-stub `callCount` uses `atomicfu` atomic (same as JVM)
 */
private class NativeStubRegistry(initial: List<StubDefinition>) : StubRegistry() {

    private val entriesRef = atomic(initial.map { StatefulEntry(it) })

    override suspend fun match(call: ApplicationCall): StubResponse? {
        val snapshot  = entriesRef.value
        val needsBody = snapshot.any { it.definition.request.body != null }
        val incoming  = IncomingRequest.from(call, needsBody)
        return matchStub(incoming, snapshot)
    }

    /** CAS loop — safe under concurrent [add] calls; no step can be lost. */
    override fun add(stub: StubDefinition) {
        entriesRef.update { it + StatefulEntry(stub) }
    }

    override fun clear() { entriesRef.value = emptyList() }

    override fun reset() { entriesRef.value.forEach { it.resetCounter() } }
}
