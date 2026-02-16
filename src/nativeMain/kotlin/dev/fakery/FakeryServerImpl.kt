package dev.fakery

import io.ktor.server.cio.*
import io.ktor.server.engine.*

internal actual fun createFakeryServer(port: Int, stubs: MutableList<StubDefinition>): FakeryServer =
    NativeFakeryServer(port, stubs)

internal class NativeFakeryServer(
    initialPort: Int,
    private val stubs: MutableList<StubDefinition>,
) : FakeryServer {

    // CIO on native handles port 0, but retrieving the resolved port requires
    // a suspending call. For test usage, accepting a fixed or random port is fine.
    private val _port: Int = if (initialPort != 0) initialPort else (49152..65535).random()
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

    override fun addStub(stub: StubDefinition) { stubs.add(stub) }

    override fun clearStubs() { stubs.clear() }
}
