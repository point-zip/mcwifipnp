@file:Suppress("unused")

package io.github.satxm.mcwifipnp.p2p

import computer.iroh.Accepting
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointId
import computer.iroh.Incoming
import computer.iroh.PathSnapshot
import computer.iroh.RecvStream
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import computer.iroh.SendStream
import kotlinx.coroutines.runBlocking

/**
 * Kotlin shim bridging the iroh-ffi bindings to the Java mod core.
 *
 * The iroh-ffi bindings expose Kotlin suspend functions and `ULong`-typed return
 * values whose JVM names carry mangle suffixes (e.g. `readToEnd-qim9Vi0`), so they
 * cannot be called directly from Java source. All suspend calls are wrapped with
 * `runBlocking` and exposed as plain `@JvmStatic` methods.
 *
 * The Java core (`io.github.satxm.mcwifipnp.p2p`) depends only on the signatures in
 * this file; no `net.minecraft.*` types appear anywhere in the P2P core.
 *
 * Usage notes (verified by the Phase 0 spike):
 *  - `applyN0()` is required: the default builder has no TLS crypto provider and
 *    `bind()` fails with "Missing or incompatible rustls crypto provider".
 *  - After `bind`, cross-NAT dialability requires the endpoint to be registered
 *    with a relay, so hosts must await `endpointOnline` before sharing their
 *    `EndpointAddr` (otherwise `relayUrl()` is null).
 *  - Direct vs relay path is available via `paths()`: a path with
 *    `pathIsRelay() == false && pathSelected() == true` means direct connectivity.
 */
object KtBridge {

    // ---- Endpoint lifecycle ----

    @JvmStatic
    fun bindEndpoint(alpns: List<ByteArray>, mode: RelayMode, secretKey: ByteArray, bindAddr: String?): Endpoint {
        val b = EndpointBuilder()
        b.applyN0()
        b.alpns(alpns)
        b.relayMode(mode)
        b.secretKey(secretKey)
        if (bindAddr != null) {
            b.bindAddr(bindAddr)
        }
        return runBlocking { b.bind() }
    }

    @JvmStatic
    fun endpointOnline(endpoint: Endpoint): Unit = runBlocking { endpoint.online() }

    @JvmStatic
    fun endpointAddr(endpoint: Endpoint): EndpointAddr = endpoint.addr()

    @JvmStatic
    fun endpointId(endpoint: Endpoint): String = endpoint.id().toString()

    @JvmStatic
    fun endpointShutdown(endpoint: Endpoint): Unit = runBlocking { endpoint.shutdown() }

    @JvmStatic
    fun endpointIsClosed(endpoint: Endpoint): Boolean = endpoint.isClosed()

    // ---- Connection ----

    @JvmStatic
    fun connect(endpoint: Endpoint, addr: EndpointAddr, alpn: ByteArray): Connection =
        runBlocking { endpoint.connect(addr, alpn) }

    @JvmStatic
    fun acceptNext(endpoint: Endpoint): Incoming? = runBlocking { endpoint.acceptNext() }

    @JvmStatic
    fun accept(incoming: Incoming): Accepting = runBlocking { incoming.accept() }

    @JvmStatic
    fun acceptConnect(accepting: Accepting): Connection = runBlocking { accepting.connect() }

    @JvmStatic
    fun acceptBi(conn: Connection): BiStream = runBlocking { conn.acceptBi() }

    @JvmStatic
    fun openBi(conn: Connection): BiStream = runBlocking { conn.openBi() }

    @JvmStatic
    fun connectionRemoteId(conn: Connection): String = conn.remoteId().toString()

    @JvmStatic
    fun connectionClose(conn: Connection): Unit = conn.close(0, ByteArray(0))

    // ---- Streams ----

    @JvmStatic
    fun readToEnd(stream: RecvStream, limit: Int): ByteArray? =
        runBlocking { stream.readToEnd(limit.toUInt()) }

    @JvmStatic
    fun read(stream: RecvStream, maxBytes: Int): ByteArray? =
        runBlocking { stream.read(maxBytes.toUInt()) }

    @JvmStatic
    fun writeAll(stream: SendStream, data: ByteArray): Unit =
        runBlocking { stream.writeAll(data) }

    @JvmStatic
    fun finish(stream: SendStream): Unit = runBlocking { stream.finish() }

    // ---- Paths (direct vs relay) ----

    @JvmStatic
    fun paths(conn: Connection): List<PathSnapshot> = conn.paths()

    @JvmStatic
    fun pathIsRelay(p: PathSnapshot): Boolean = p.isRelay

    @JvmStatic
    fun pathIsSelected(p: PathSnapshot): Boolean = p.isSelected

    @JvmStatic
    fun pathRemoteAddr(p: PathSnapshot): String = p.remoteAddr

    // ---- EndpointAddr ----

    @JvmStatic
    fun endpointAddrId(addr: EndpointAddr): String = addr.id().toString()

    @JvmStatic
    fun endpointAddrRelayUrl(addr: EndpointAddr): String? = addr.relayUrl()

    @JvmStatic
    fun endpointAddrDirectAddresses(addr: EndpointAddr): List<String> = addr.directAddresses()

    @JvmStatic
    fun makeEndpointAddr(id: String, relayUrl: String?, directAddrs: List<String>): EndpointAddr {
        val endpointId: EndpointId = EndpointId.Companion.fromString(id)
        return EndpointAddr(endpointId, relayUrl, directAddrs)
    }

    // ---- Relay mode ----

    @JvmStatic
    fun relayModeDefault(): RelayMode = RelayMode.Companion.defaultMode()

    @JvmStatic
    fun relayModeDisabled(): RelayMode = RelayMode.Companion.disabled()

    @JvmStatic
    fun relayModeCustomFromUrls(urls: List<String>): RelayMode =
        RelayMode.Companion.customFromUrls(urls)

    // ---- Secret key ----

    @JvmStatic
    fun generateSecretKey(): ByteArray = SecretKey.Companion.generate().toBytes()
}
