package dev.anvith.fakery

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

    override fun addStub(stub: StubDefinition)                         = registry.add(stub)
    override fun clearStubs()                                          = registry.clear()
    override fun reset()                                               = registry.reset()
    override fun getCallCount(method: String, path: String): Int       = registry.getCallCount(method, path)

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

/**
 * JVM-specific [StubRegistry].
 *
 * - `CopyOnWriteArrayList` — thread-safe structural mutations (add / clear)
 * - Body parsed once per request via [IncomingRequest.from] before iterating
 * - Per-stub `callCount` uses `atomicfu` atomic — no skipped steps under concurrency
 */
private class JvmStubRegistry(initial: List<StubDefinition>) : StubRegistry() {

    private val entries = CopyOnWriteArrayList(initial.map { StatefulEntry(it) })

    override suspend fun match(call: ApplicationCall): StubResponse? {
        val snapshot  = entries.toList()
        val needsBody = snapshot.any { entry ->
            entry.definition.request.body != null || entry.definition.request.bodyContains != null
        }
        val incoming  = IncomingRequest.from(call, needsBody)
        return matchStub(incoming, snapshot)
    }

    override fun add(stub: StubDefinition) { entries.add(StatefulEntry(stub)) }

    override fun clear() { entries.clear() }

    override fun reset() { entries.forEach { it.resetCounter() } }

    override fun getCallCount(method: String, path: String): Int =
        entries.firstOrNull { entry ->
            val req = entry.definition.request
            req.method.equals(method, ignoreCase = true) && req.path == path
        }?.currentCallCount ?: 0
}
