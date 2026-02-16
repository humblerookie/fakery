package dev.fakery

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.net.ServerSocket

internal actual fun createFakeryServer(port: Int, stubs: MutableList<StubDefinition>): FakeryServer =
    JvmFakeryServer(port, stubs)

internal class JvmFakeryServer(
    initialPort: Int,
    private val stubs: MutableList<StubDefinition>,
) : FakeryServer {

    private val _port: Int = if (initialPort != 0) initialPort else findFreePort()
    override val port: Int get() = _port
    override val baseUrl: String get() = "http://localhost:$_port"

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun start() {
        server = embeddedServer(CIO, port = _port) {
            fakeryModule(stubs)
        }.start(wait = false)
    }

    override fun stop() {
        server?.engine?.stop(gracePeriodMillis = 100L, timeoutMillis = 500L)
        server = null
    }

    override fun addStub(stub: StubDefinition) { synchronized(stubs) { stubs.add(stub) } }

    override fun clearStubs() { synchronized(stubs) { stubs.clear() } }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
