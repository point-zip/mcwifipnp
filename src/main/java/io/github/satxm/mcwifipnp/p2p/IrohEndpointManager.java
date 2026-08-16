package io.github.satxm.mcwifipnp.p2p;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import computer.iroh.Connection;
import computer.iroh.Endpoint;
import computer.iroh.EndpointAddr;
import computer.iroh.RelayMode;

/**
 * Wraps the iroh endpoint lifecycle for the mod. Version-agnostic: no Minecraft
 * types here; the Kotlin shim {@code KtBridge} does all the FFI bridging.
 *
 * <p>An endpoint is a UDP socket plus its keypair. To be dialable across a NAT the
 * endpoint must register with a relay (see {@link #awaitOnline(long)}), which the
 * caller does before sharing the EndpointAddr with the peer.
 */
public final class IrohEndpointManager {

	/** ALPN negotiated on iroh connections. */
	public static final String ALPN = "mcwifipnp-p2p/1";

	public static final long DEFAULT_ONLINE_TIMEOUT_MS = 15_000L;

	private Endpoint endpoint;
	private String bindAddr;

	/** Create the endpoint. Idempotent: repeated calls keep the existing endpoint. */
	public synchronized void init() {
		if (this.endpoint != null && !KtBridge.endpointIsClosed(this.endpoint)) {
			return;
		}
		byte[] secretKey = KtBridge.generateSecretKey();
		List<byte[]> alpns = new ArrayList<byte[]>();
		alpns.add(ALPN.getBytes(StandardCharsets.UTF_8));
		RelayMode mode = KtBridge.relayModeDefault();
		this.endpoint = KtBridge.bindEndpoint(alpns, mode, secretKey, this.bindAddr);
	}

	/** Bind the UDP socket to a specific local address (e.g. 127.0.0.1 for tests). Null = ephemeral. */
	public synchronized void setBindAddr(String addr) {
		this.bindAddr = addr;
	}

	/** Block until the endpoint is registered with a relay, or the timeout elapses. Returns success. */
	public boolean awaitOnline(long timeoutMillis) {
		Endpoint ep = this.endpoint;
		if (ep == null) {
			return false;
		}
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (isOnline()) {
				return true;
			}
			try {
				KtBridge.endpointOnline(ep);
				return true;
			} catch (Throwable t) {
				try {
					Thread.sleep(200L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		}
		return false;
	}

	/** True when the endpoint is registered with a relay (and therefore dialable). */
	public boolean isOnline() {
		Endpoint ep = this.endpoint;
		if (ep == null) {
			return false;
		}
		try {
			EndpointAddr addr = KtBridge.endpointAddr(ep);
			return KtBridge.endpointAddrRelayUrl(addr) != null;
		} catch (Throwable t) {
			return false;
		}
	}

	/** The endpoint's address, serialized to a string for transport over the game channel. */
	public String getEndpointAddrString() {
		EndpointAddr addr = KtBridge.endpointAddr(this.endpoint);
		return KtBridge.endpointAddrId(addr) + "|" + KtBridge.endpointAddrRelayUrl(addr) + "|"
				+ String.join(",", KtBridge.endpointAddrDirectAddresses(addr));
	}

	/** Parse a serialized endpoint address back into an iroh EndpointAddr. */
	public static EndpointAddr parseEndpointAddr(String serialized) {
		int first = serialized.indexOf('|');
		int second = serialized.indexOf('|', first + 1);
		if (first < 0 || second < 0) {
			throw new IllegalArgumentException("malformed endpoint addr: " + serialized);
		}
		String id = serialized.substring(0, first);
		String relay = serialized.substring(first + 1, second);
		String direct = serialized.substring(second + 1);
		List<String> directAddrs = direct.isEmpty()
				? new ArrayList<String>()
				: Arrays.asList(direct.split(","));
		return KtBridge.makeEndpointAddr(id, relay.isEmpty() ? null : relay, directAddrs);
	}

	public synchronized void shutdown() {
		if (this.endpoint != null) {
			try {
				KtBridge.endpointShutdown(this.endpoint);
			} catch (Throwable t) {
				// best effort
			}
			this.endpoint = null;
		}
	}

	/** The live iroh endpoint, or null if not initialized. */
	public Endpoint getEndpoint() {
		return this.endpoint;
	}
}
