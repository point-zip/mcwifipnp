import computer.iroh.*
import kotlinx.coroutines.runBlocking

/**
 * Shim exposing iroh-ffi suspend/ULong-mangled APIs as plain Java-callable methods.
 * The mod core (Java 8 compatible) calls only these entry points.
 */
object KtBridge {
    // ---- Endpoint lifecycle ----
    @JvmStatic
    fun bindEndpoint(alpns: List<ByteArray>, mode: RelayMode, secretKey: ByteArray): Endpoint =
        runBlocking {
            val b = EndpointBuilder()
            b.applyN0()
            b.alpns(alpns)
            b.relayMode(mode)
            b.secretKey(secretKey)
            b.bind()
        }

    // ---- Connection ----
    @JvmStatic
    fun connect(endpoint: Endpoint, addr: EndpointAddr, alpn: ByteArray): Connection =
        runBlocking { endpoint.connect(addr, alpn) }

    @JvmStatic
    fun acceptNext(endpoint: Endpoint): Incoming? =
        runBlocking { endpoint.acceptNext() }

    @JvmStatic
    fun accept(incoming: Incoming): Accepting =
        runBlocking { incoming.accept() }

    @JvmStatic
    fun acceptConnect(accepting: Accepting): Connection =
        runBlocking { accepting.connect() }

    @JvmStatic
    fun acceptBi(conn: Connection): BiStream =
        runBlocking { conn.acceptBi() }

    @JvmStatic
    fun openBi(conn: Connection): BiStream =
        runBlocking { conn.openBi() }

    @JvmStatic
    fun finish(stream: SendStream): Unit =
        runBlocking { stream.finish() }

    @JvmStatic
    fun readToEnd(stream: RecvStream, limit: Int): ByteArray? =
        runBlocking { stream.readToEnd(limit.toUInt()) }

    @JvmStatic
    fun readExact(stream: RecvStream, length: Int): ByteArray? =
        runBlocking { stream.readExact(length.toUInt()) }

    @JvmStatic
    fun read(stream: RecvStream, maxBytes: Int): ByteArray? =
        runBlocking { stream.read(maxBytes.toUInt()) }

    @JvmStatic
    fun writeAll(stream: SendStream, data: ByteArray): Unit =
        runBlocking { stream.writeAll(data) }
}
