package dev.fakery

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

internal actual fun createFakeryServer(port: Int, stubs: MutableList<StubDefinition>): FakeryServer =
    NativeFakeryServer(port, stubs)

internal class NativeFakeryServer(
    private val initialPort: Int,
    initialStubs: List<StubDefinition>,
) : FakeryServer {

    /**
     * @Volatile ensures that writes from the test thread (addStub / clearStubs) are
     * immediately visible to the server thread. We replace the entire list reference
     * atomically so the server always iterates a stable, immutable snapshot.
     */
    @Volatile private var stubs: List<StubDefinition> = initialStubs.toList()

    private var _port: Int = 0
    override val port: Int get() = _port
    override val baseUrl: String get() = "http://localhost:$_port"

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun start() {
        server = embeddedServer(CIO, port = initialPort) {
            fakeryModule { stubs }
        }.start(wait = false)

        // resolvedConnectors() suspends until the OS has actually bound the port,
        // giving us the real port even when initialPort == 0.
        _port = runBlocking { server!!.engine.resolvedConnectors().first().port }
    }

    override fun stop() {
        server?.engine?.stop(gracePeriodMillis = 100L, timeoutMillis = 500L)
        server = null
    }

    /** Appends the stub; creates a new list to keep the snapshot guarantee. */
    override fun addStub(stub: StubDefinition) {
        stubs = stubs + stub
    }

    /** Replaces the list atomically — any in-flight request continues on the old snapshot. */
    override fun clearStubs() {
        stubs = emptyList()
    }
}
