package io.github.satxm.mcwifipnp.p2p;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import computer.iroh.BiStream;
import computer.iroh.Connection;
import computer.iroh.EndpointAddr;
import computer.iroh.Incoming;
import computer.iroh.PathSnapshot;

/**
 * Version-agnostic P2P hole-punching state machine.
 *
 * <p>Host side: when a member joins over the frp path, an OFFER is sent. After the
 * member answers with a HELLO (token + iroh endpoint), the host ACCEPTs (or DENYs)
 * and then runs an accept loop that bridges each incoming iroh stream to the local
 * game server port. When the stream is up, a SWITCH_READY tells the member to
 * reconnect its game client to the member-side local proxy.
 *
 * <p>Member side: on OFFER a local TCP proxy is bound on 127.0.0.1, the host is
 * dialed over iroh, and on SWITCH_READY the game client is reconnected to the proxy.
 *
 * <p>All platform interaction (packet transport, reconnect, chat messages) goes
 * through the {@link P2PHandler}; this class contains no Minecraft types.
 */
public final class P2PManager {

	private static final String LOCAL_PROXY_HOST = "127.0.0.1";

	private static final P2PManager INSTANCE = new P2PManager();

	/** Global singleton used by the platform adapters and commands. */
	public static P2PManager getInstance() {
		return INSTANCE;
	}

	private final IrohEndpointManager iroh = new IrohEndpointManager();
	private final Map<String, String> endpointIdToPlayer = new ConcurrentHashMap<String, String>();

	private P2PHandler handler;
	private volatile boolean hostMode;
	private volatile int serverPort = 25565;
	private volatile String token;
	private volatile boolean tokenRequired;
	private volatile boolean autoSwitch = true;
	private volatile boolean enabled;

	// member side state
	private volatile boolean memberEnabled = true;
	private volatile boolean memberRequesting;
	private volatile ServerSocket memberProxyServer;
	private volatile BiStream memberBiStream;
	private volatile P2PMessage pendingOffer;
	private volatile String memberHostAddrString;
	private volatile boolean pendingConsent;

	// host side accept loop
	private volatile Thread acceptThread;
	private volatile boolean acceptLoopRunning;

	public synchronized void setHandler(P2PHandler handler) {
		this.handler = handler;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (!enabled) {
			this.shutdown();
		}
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getToken() {
		return this.token;
	}

	public void setTokenRequired(boolean required) {
		this.tokenRequired = required;
	}

	public boolean isTokenRequired() {
		return this.tokenRequired;
	}

	public void setAutoSwitch(boolean autoSwitch) {
		this.autoSwitch = autoSwitch;
	}

	public boolean isAutoSwitch() {
		return this.autoSwitch;
	}

	/** Whether the member participates in P2P hole punching at all. Default true. */
	public void setMemberEnabled(boolean enabled) {
		this.memberEnabled = enabled;
		if (!enabled) {
			this.memberRequesting = false;
			this.cleanupMemberSide();
		}
	}

	public boolean isMemberEnabled() {
		return this.memberEnabled;
	}

	/** The shared iroh endpoint manager. */
	public IrohEndpointManager getIroh() {
		return this.iroh;
	}

	// ------------------------------------------------------------------
	// Host side
	// ------------------------------------------------------------------

	/** Start the host side for the given game server port. Idempotent. */
	public synchronized void startHost(int gameServerPort) {
		if (this.hostMode) {
			return;
		}
		this.hostMode = true;
		this.serverPort = gameServerPort;
		this.iroh.init();
		startAcceptLoop();
	}

	/** Stop the host side. */
	public synchronized void stopHost() {
		this.hostMode = false;
		this.acceptLoopRunning = false;
		Thread t = this.acceptThread;
		if (t != null) {
			t.interrupt();
			this.acceptThread = null;
		}
	}

	/** A member joined via the frp path: ask whether they want a direct connection. */
	public void onMemberJoined(String playerName) {
		if (!this.enabled || !this.hostMode) {
			return;
		}
		P2PHandler h = this.handler;
		if (h != null) {
			h.sendToClient(playerName, P2PMessage.consentRequest());
		}
	}

	/** A member answered the offer: validate the token and reply with accept/deny. */
	public void onHelloFromMember(String playerName, String memberEndpointAddrString, String suppliedToken) {
		if (!this.hostMode) {
			return;
		}
		if (this.tokenRequired && (suppliedToken == null || !suppliedToken.equals(this.token))) {
			P2PHandler h = this.handler;
			if (h != null) {
				h.sendToClient(playerName, P2PMessage.deny());
				h.notify("mcwifipnp.p2p.member_token_rejected");
			}
			return;
		}
		this.endpointIdToPlayer.put(extractEndpointId(memberEndpointAddrString), playerName);
		P2PHandler h = this.handler;
		if (h != null) {
			h.sendToClient(playerName, P2PMessage.accept(this.iroh.getEndpointAddrString()));
		}
	}

	private void startAcceptLoop() {
		this.acceptLoopRunning = true;
		this.acceptThread = new Thread(new Runnable() {
			@Override
			public void run() {
				acceptLoop();
			}
		}, "MCWiFiPnP_P2P-accept");
		this.acceptThread.setDaemon(true);
		this.acceptThread.start();
	}

	private void acceptLoop() {
		while (this.acceptLoopRunning && this.hostMode) {
			try {
				Incoming incoming = KtBridge.acceptNext(this.iroh.getEndpoint());
				if (incoming == null) {
					continue;
				}
				Connection conn = KtBridge.acceptConnect(KtBridge.accept(incoming));
				String remoteId = KtBridge.connectionRemoteId(conn);
				handleHostConnection(remoteId, conn);
			} catch (Throwable t) {
				if (this.acceptLoopRunning) {
					try {
						Thread.sleep(500L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
		}
	}

	private void handleHostConnection(String remoteId, Connection conn) {
		try {
			BiStream stream = KtBridge.acceptBi(conn);
			String playerName = this.endpointIdToPlayer.get(remoteId);
			if (playerName == null) {
				// Unknown peer: close the stream.
				KtBridge.connectionClose(conn);
				return;
			}
			// The member pushes a single marker byte so that the opened stream
			// materializes on this side (QUIC RFC 9000 only notifies the peer of a
			// new stream when a frame is actually sent). Consume it to keep the
			// game data stream clean.
			KtBridge.read(stream.recv(), 1);
			// Bridge the stream to the local game server port.
			Socket serverSocket = new Socket();
			serverSocket.connect(new InetSocketAddress(LOCAL_PROXY_HOST, this.serverPort), 5000);
			P2PTunnel tunnel = new P2PTunnel(serverSocket, stream, "p2p-host-" + playerName);
			Thread tunnelThread = new Thread(tunnel, "MCWiFiPnP_P2P-tunnel-" + playerName);
			tunnelThread.setDaemon(true);
			tunnelThread.start();
			if (this.autoSwitch) {
				P2PHandler h = this.handler;
				if (h != null) {
					h.sendToClient(playerName, P2PMessage.switchReady(0));
				}
			}
		} catch (Throwable t) {
			// local server not reachable or stream error: leave the member on frp.
			try {
				KtBridge.connectionClose(conn);
			} catch (Throwable ignored) {
				// ignore
			}
		}
	}

	// ------------------------------------------------------------------
	// Member side
	// ------------------------------------------------------------------

	/** Start the member side (called when P2P config is enabled while connected). */
	public synchronized void startMember() {
		if (this.hostMode) {
			return;
		}
		this.iroh.init();
	}

	/** The host asked whether we want a direct connection. Ask the player. */
	public synchronized void onConsentRequest() {
		if (this.hostMode || !this.memberEnabled) {
			return;
		}
		this.pendingConsent = true;
		P2PHandler h = this.handler;
		if (h != null) {
			h.notify("mcwifipnp.p2p.consent_ask");
		}
	}

	/**
	 * The member wants a direct connection right now (from /p2p connect, or
	 * automatically when P2P is enabled). Tells the host to start hole punching.
	 */
	public synchronized void onMemberConnectRequested() {
		if (this.hostMode || !this.memberEnabled) {
			return;
		}
		// Already in a hole-punching attempt or tunneled: ignore.
		if (this.memberRequesting || this.memberBiStream != null || this.pendingOffer != null) {
			return;
		}
		this.memberRequesting = true;
		P2PHandler h = this.handler;
		if (h != null) {
			h.sendToServer(P2PMessage.requestHolepunch());
		}
	}

	/** The player accepted (/p2p allow): tell the host to proceed. */
	public synchronized void onConsentAccepted() {
		if (this.hostMode || !this.pendingConsent) {
			return;
		}
		this.pendingConsent = false;
		P2PHandler h = this.handler;
		if (h != null) {
			h.sendToServer(P2PMessage.consentAccept());
		}
	}

	/** The player declined (/p2p deny): stay on the frp path. */
	public synchronized void onConsentDenied() {
		if (this.hostMode) {
			return;
		}
		this.pendingConsent = false;
	}

	/** The member accepted: offer hole punching to them. */
	public void onConsentAcceptedFromMember(String playerName) {
		if (!this.hostMode) {
			return;
		}
		this.iroh.init();
		this.iroh.awaitOnline(IrohEndpointManager.DEFAULT_ONLINE_TIMEOUT_MS);
		P2PHandler h = this.handler;
		if (h != null) {
			h.sendToClient(playerName, P2PMessage.offer(this.tokenRequired));
		}
	}

	/** The host offered hole punching. */
	public synchronized void onOfferFromHost(boolean requiresToken) {
		if (this.hostMode) {
			return;
		}
		this.memberRequesting = false;
		if (requiresToken && (this.token == null || this.token.isEmpty())) {
			// Wait for the player to provide a token via /p2p token.
			this.pendingOffer = P2PMessage.offer(true);
			P2PHandler h = this.handler;
			if (h != null) {
				h.notify("mcwifipnp.p2p.need_token");
			}
			return;
		}
		this.pendingOffer = null;
		startMemberProxyAndHello(requiresToken);
	}

	/** The player typed a token: retry a pending offer, if any. */
	public void onTokenProvided() {
		if (this.pendingOffer != null && !this.hostMode && this.enabled) {
			P2PMessage offer = this.pendingOffer;
			this.pendingOffer = null;
			startMemberProxyAndHello(offer.isTokenRequired());
		}
	}

	private void startMemberProxyAndHello(boolean requiresToken) {
		try {
			this.iroh.init();
			this.iroh.awaitOnline(IrohEndpointManager.DEFAULT_ONLINE_TIMEOUT_MS);
			ServerSocket proxy = new ServerSocket();
			proxy.bind(new InetSocketAddress(LOCAL_PROXY_HOST, 0));
			this.memberProxyServer = proxy;
			P2PHandler h = this.handler;
			if (h != null) {
				h.sendToServer(P2PMessage.hello(requiresToken ? this.token : null, this.iroh.getEndpointAddrString()));
			}
		} catch (IOException e) {
			P2PHandler h = this.handler;
			if (h != null) {
				h.notify("mcwifipnp.p2p.member_proxy_failed");
			}
		}
	}

	/** The host accepted: dial the host over iroh and open the data stream. */
	public void onAcceptFromHost(String hostEndpointAddrString) {
		if (this.hostMode) {
			return;
		}
		this.memberHostAddrString = hostEndpointAddrString;
		Thread dialThread = new Thread(new Runnable() {
			@Override
			public void run() {
				dialHost(hostEndpointAddrString);
			}
		}, "MCWiFiPnP_P2P-dial");
		dialThread.setDaemon(true);
		dialThread.start();
	}

	private void dialHost(String hostEndpointAddrString) {
		try {
			EndpointAddr hostAddr = IrohEndpointManager.parseEndpointAddr(hostEndpointAddrString);
			Connection conn = KtBridge.connect(this.iroh.getEndpoint(), hostAddr,
					IrohEndpointManager.ALPN.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			BiStream stream = KtBridge.openBi(conn);
			this.memberBiStream = stream;
			// Push a marker byte so the host's acceptBi returns (QUIC RFC 9000 does
			// not notify the peer of a new stream until a frame is sent). The host
			// consumes this byte; the game data stream stays clean.
			KtBridge.writeAll(stream.send(), new byte[] { (byte) 0x00 });
			// Signal the host that we are ready; the host answers with SWITCH_READY
			// once its side of the tunnel is connected to the game server.
			P2PHandler h = this.handler;
			if (h != null) {
				h.sendToServer(P2PMessage.tunnelReady());
			}
			P2PManager.this.acceptLocalClients();
		} catch (Throwable t) {
			P2PHandler h = this.handler;
			if (h != null) {
				h.notify("mcwifipnp.p2p.member_dial_failed");
			}
		}
	}

	/** Host rejected us: stay on the frp path. */
	public void onDenyFromHost() {
		this.cleanupMemberSide();
		P2PHandler h = this.handler;
		if (h != null) {
			h.notify("mcwifipnp.p2p.member_denied");
		}
	}

	/** Host told us to switch: reconnect the game client to the local proxy. */
	public void onSwitchReadyFromHost(int ignoredProxyPort) {
		ServerSocket proxy = this.memberProxyServer;
		if (proxy == null) {
			return;
		}
		P2PHandler h = this.handler;
		if (h != null) {
			h.requestReconnect(LOCAL_PROXY_HOST, proxy.getLocalPort());
		}
		acceptLocalClients();
	}

	private void acceptLocalClients() {
		ServerSocket proxy = this.memberProxyServer;
		BiStream stream = this.memberBiStream;
		if (proxy == null || stream == null) {
			return;
		}
		Thread acceptThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Socket client = proxy.accept();
					P2PTunnel tunnel = new P2PTunnel(client, stream, "p2p-member");
					Thread tunnelThread = new Thread(tunnel, "MCWiFiPnP_P2P-tunnel-member");
					tunnelThread.setDaemon(true);
					tunnelThread.start();
				} catch (IOException e) {
					// proxy closed: nothing to do
				}
			}
		}, "MCWiFiPnP_P2P-proxy");
		acceptThread.setDaemon(true);
		acceptThread.start();
	}

	// ------------------------------------------------------------------
	// Message dispatch (called from the platform adapters)
	// ------------------------------------------------------------------

	/** serverbound: a message arrived from a connected member. */
	public void handleServerbound(String playerName, P2PMessage message) {
		switch (message.getType()) {
			case P2PMessage.TYPE_HELLO:
				this.onHelloFromMember(playerName, message.getEndpointAddr(), message.getToken());
				break;
			case P2PMessage.TYPE_CONSENT_ACCEPT:
				this.onConsentAcceptedFromMember(playerName);
				break;
			case P2PMessage.TYPE_REQUEST_HOLEPUNCH:
				// The member asked for a direct connection: same as if they accepted.
				this.onConsentAcceptedFromMember(playerName);
				break;
			case P2PMessage.TYPE_TUNNEL_READY:
				// The member's iroh stream is up; nothing further is needed here,
				// the accept loop already bridges the stream and sends SWITCH_READY.
				break;
			default:
				break;
		}
	}

	/** clientbound: a message arrived from the host. */
	public void handleClientbound(P2PMessage message) {
		switch (message.getType()) {
			case P2PMessage.TYPE_CONSENT_REQUEST:
				this.onConsentRequest();
				break;
			case P2PMessage.TYPE_OFFER:
				this.onOfferFromHost(message.isTokenRequired());
				break;
			case P2PMessage.TYPE_ACCEPT:
				this.onAcceptFromHost(message.getEndpointAddr());
				break;
			case P2PMessage.TYPE_DENY:
				this.onDenyFromHost();
				break;
			case P2PMessage.TYPE_SWITCH_READY:
				this.onSwitchReadyFromHost(message.getProxyPort());
				break;
			default:
				break;
		}
	}

	// ------------------------------------------------------------------
	// Shared
	// ------------------------------------------------------------------

	/** True when the current iroh connection is direct (not relayed). */
	public static boolean isDirectPath(Connection conn) {
		try {
			for (PathSnapshot path : KtBridge.paths(conn)) {
				if (!KtBridge.pathIsRelay(path) && KtBridge.pathIsSelected(path)) {
					return true;
				}
			}
		} catch (Throwable t) {
			return false;
		}
		return false;
	}

	/** Reset all state (host and member). */
	public synchronized void shutdown() {
		this.stopHost();
		this.cleanupMemberSide();
		this.endpointIdToPlayer.clear();
		this.pendingOffer = null;
		this.memberHostAddrString = null;
		this.pendingConsent = false;
		this.memberRequesting = false;
		this.iroh.shutdown();
	}

	private void cleanupMemberSide() {
		ServerSocket proxy = this.memberProxyServer;
		this.memberProxyServer = null;
		if (proxy != null) {
			try {
				proxy.close();
			} catch (IOException e) {
				// ignore
			}
		}
		BiStream stream = this.memberBiStream;
		this.memberBiStream = null;
		if (stream != null) {
			try {
				stream.close();
			} catch (Throwable t) {
				// ignore
			}
		}
	}

	private static String extractEndpointId(String serialized) {
		int first = serialized.indexOf('|');
		return first < 0 ? serialized : serialized.substring(0, first);
	}
}
