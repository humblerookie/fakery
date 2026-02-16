package dev.fakery

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
    stubs: MutableList<StubDefinition>,
) : FakeryServer {

    /**
     * CopyOnWriteArrayList gives us:
     * - Thread-safe writes (addStub / clearStubs)
     * - Lock-free reads that iterate over a stable snapshot
     * This eliminates the ConcurrentModificationException risk when clearStubs()
     * is called during an in-flight request.
     */
    private val stubs: CopyOnWriteArrayList<StubDefinition> = CopyOnWriteArrayList(stubs)

    private val _port: Int = if (initialPort != 0) initialPort else findFreePort()
    override val port: Int get() = _port
    override val baseUrl: String get() = "http://localhost:$_port"

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun start() {
        server = embeddedServer(CIO, port = _port) {
            // Pass a snapshot lambda — CopyOnWriteArrayList.toList() is O(n) but
            // returns a stable copy safe for iteration without holding any lock.
            fakeryModule { stubs.toList() }
        }.start(wait = false)
    }

    override fun stop() {
        server?.engine?.stop(gracePeriodMillis = 100L, timeoutMillis = 500L)
        server = null
    }

    override fun addStub(stub: StubDefinition) { stubs.add(stub) }

    override fun clearStubs() { stubs.clear() }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
