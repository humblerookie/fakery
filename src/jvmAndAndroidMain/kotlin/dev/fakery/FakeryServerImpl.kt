package dev.fakery

import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

internal actual fun createFakeryServer(port: Int, stubs: MutableList<StubDefinition>): FakeryServer =
    JvmFakeryServer(port, stubs)

internal class JvmFakeryServer(
    initialPort: Int,
    stubs: List<StubDefinition>,
) : FakeryServer {

    private val registry = JvmStubRegistry(stubs)

    private val _port: Int = if (initialPort != 0) initialPort else findFreePort()
    override val port: Int get() = _port
    override val baseUrl: String get() = "http://localhost:$_port"

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun start() {
        server = embeddedServer(CIO, port = _port) {
            fakeryModule(registry)
        }.start(wait = false)
    }

    override fun stop() {
        server?.engine?.stop(gracePeriodMillis = 100L, timeoutMillis = 500L)
        server = null
    }

    override fun addStub(stub: StubDefinition) = registry.add(stub)
    override fun clearStubs()                  = registry.clear()
    override fun reset()                       = registry.reset()

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

/**
 * JVM-specific [StubRegistry] backed by a [CopyOnWriteArrayList].
 * Reads (match) iterate a stable snapshot; writes (add/clear/reset) are thread-safe.
 */
private class JvmStubRegistry(initial: List<StubDefinition>) : StubRegistry() {

    private val entries = CopyOnWriteArrayList(initial.map { StatefulEntry(it) })

    override suspend fun match(call: ApplicationCall): StubResponse? =
        matchStub(call, entries.toList())

    override fun add(stub: StubDefinition) { entries.add(StatefulEntry(stub)) }

    override fun clear() { entries.clear() }

    override fun reset() { entries.forEach { it.resetCounter() } }
}
