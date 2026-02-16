package dev.fakery

import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
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
 * Native [StubRegistry] using a `@Volatile` list reference for safe snapshots.
 * Writes replace the entire list atomically; reads always see a stable snapshot.
 */
private class NativeStubRegistry(initial: List<StubDefinition>) : StubRegistry() {

    @Volatile private var entries: List<StatefulEntry> = initial.map { StatefulEntry(it) }

    override suspend fun match(call: ApplicationCall): StubResponse? =
        matchStub(call, entries)

    override fun add(stub: StubDefinition) {
        entries = entries + StatefulEntry(stub)
    }

    override fun clear() { entries = emptyList() }

    override fun reset() { entries.forEach { it.resetCounter() } }
}
