import io.github.satxm.mcwifipnp.p2p.P2PHandler;
import io.github.satxm.mcwifipnp.p2p.P2PManager;
import io.github.satxm.mcwifipnp.p2p.P2PMessage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * End-to-end test of the P2P core (no Minecraft): two P2PManager instances in one
 * JVM, bridged through a fake packet channel, over a real iroh connection.
 *
 * Flow: host starts + fake game server -> member joins -> offer/hello/accept ->
 * member dials -> iroh bi-stream established -> host bridges to fake server ->
 * switch-ready -> test client connects to member proxy -> data round-trips
 * through the iroh tunnel to the fake server and back.
 */
public class P2PE2ETest {

	static final String PLAYER = "member";
	static volatile int proxyPort = -1;
	static volatile boolean switched = false;
	static final Object switchLock = new Object();

	/** A tiny echo server standing in for the host's game server. */
	static class FakeGameServer extends Thread {
		final ServerSocket server;
		volatile boolean running = true;
		FakeGameServer(int port) throws Exception {
			this.server = new ServerSocket(port);
			setDaemon(true);
			start();
		}
		public void run() {
			while (running) {
				try {
					Socket s = server.accept();
					Thread t = new Thread(() -> {
						try {
							InputStream in = s.getInputStream();
							OutputStream out = s.getOutputStream();
							byte[] buf = new byte[4096];
							int n;
							while ((n = in.read(buf)) != -1) {
								out.write(buf, 0, n);
								out.flush();
							}
							s.close();
						} catch (Exception ignored) {
						}
					}, "fake-game-server-conn");
					t.setDaemon(true);
					t.start();
				} catch (Exception ignored) {
				}
			}
		}
	}

	static class Bridge implements P2PHandler {
		final P2PManager peer;
		final String name;
		Bridge(P2PManager peer, String name) { this.peer = peer; this.name = name; }

		@Override public void sendToClient(String playerName, P2PMessage message) {
			System.out.println("[bridge] " + name + " -> member: " + message);
			peer.handleClientbound(message);
		}
		@Override public void sendToServer(P2PMessage message) {
			System.out.println("[bridge] " + name + " -> host: " + message);
			peer.handleServerbound(PLAYER, message);
		}
		@Override public void requestReconnect(String host, int port) {
			System.out.println("[test] SWITCH: reconnect to " + host + ":" + port);
			proxyPort = port;
			switched = true;
			synchronized (switchLock) { switchLock.notifyAll(); }
		}
		@Override public void notify(String message) {
			System.out.println("[test] " + name + " notify: " + message);
		}
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== P2P E2E test on " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + " ===");

		// 1. Fake game server on an ephemeral port.
		int serverPort = 25599;
		FakeGameServer fakeServer = new FakeGameServer(serverPort);
		System.out.println("[test] fake game server on " + serverPort);

		// 2. Host and member P2P managers, bridged via fake packets.
		P2PManager host = new P2PManager();
		P2PManager member = new P2PManager();
		host.setHandler(new Bridge(member, "host"));
		member.setHandler(new Bridge(host, "member"));

		host.setEnabled(true);
		host.setToken(null);
		host.setTokenRequired(false);
		host.setAutoSwitch(true);
		host.startHost(serverPort);

		member.setEnabled(true);
		member.startMember();

		// 3. Member "joins" over the (simulated) frp path.
		Thread.sleep(500);
		System.out.println("[test] member joined; host offers");
		host.onMemberJoined(PLAYER);

		// The offer/hello/accept round-trip happens through the bridge; give the
		// member's dial thread time to establish the iroh connection.
		long deadline = System.currentTimeMillis() + 60_000;
		synchronized (switchLock) {
			while (!switched && System.currentTimeMillis() < deadline) {
				switchLock.wait(2000);
			}
		}

		if (!switched) {
			System.out.println("[test] FAIL: never switched");
			host.shutdown();
			member.shutdown();
			System.exit(1);
		}

		// 4. A local client connects to the member-side proxy; data must travel
		// through the iroh tunnel to the fake game server and back.
		try (Socket client = new Socket("127.0.0.1", proxyPort)) {
			client.setSoTimeout(15000);
			PrintWriter out = new PrintWriter(client.getOutputStream(), true);
			BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
			String payload = "hello-through-p2p-tunnel";
			out.println(payload);
			String reply = in.readLine();
			if (payload.equals(reply)) {
				System.out.println("[test] PASS: tunnel round-trip echoed \"" + reply + "\"");
			} else {
				System.out.println("[test] FAIL: echo mismatch, got \"" + reply + "\"");
				System.exit(1);
			}
		}

		host.shutdown();
		member.shutdown();
		System.out.println("[test] DONE (pass)");
	}
}
