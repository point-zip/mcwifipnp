import computer.iroh.*;
import java.util.Arrays;
import java.util.List;

/**
 * Spike: verify computer.iroh:iroh:1.1.0 works from pure Java (via KtBridge shim)
 * on Windows + Java 25. Two endpoints in one JVM (host + client), connect over
 * loopback, exchange bytes over a bidirectional stream, then inspect paths.
 */
public class Spike {
    static final byte[] ALPN = "mcwifipnp-p2p/1".getBytes();

    static byte[] newKey(int seed) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * seed + 3);
        return key;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[spike] java " + System.getProperty("java.version")
                + " os " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        // Host endpoint
        Endpoint host = KtBridge.bindEndpoint(Arrays.asList(ALPN), RelayMode.Companion.defaultMode(), newKey(7));
        EndpointAddr hostAddr = host.addr();
        String hostId = hostAddr.id().toString();
        String relay = hostAddr.relayUrl();
        List<String> directs = hostAddr.directAddresses();
        System.out.println("[spike] host id=" + hostId);
        System.out.println("[spike] host relay=" + relay);
        System.out.println("[spike] host direct addrs=" + directs);

        // Host accept loop on background thread
        Thread acceptThread = new Thread(() -> {
            try {
                Incoming incoming = KtBridge.acceptNext(host);
                Accepting accepting = KtBridge.accept(incoming);
                Connection conn = KtBridge.acceptConnect(accepting);
                BiStream bi = KtBridge.acceptBi(conn);
                byte[] data = KtBridge.readToEnd(bi.recv(), 1024);
                System.out.println("[spike] HOST received: " + new String(data));
                System.out.println("[spike] HOST paths: " + describePaths(conn));
                conn.close(0, new byte[0]);
                incoming.close();
            } catch (Exception e) {
                System.out.println("[spike] HOST accept failed: " + e);
                e.printStackTrace();
            }
        }, "spike-host");
        acceptThread.start();

        // Client endpoint and dial
        Endpoint client = KtBridge.bindEndpoint(Arrays.asList(ALPN), RelayMode.Companion.defaultMode(), newKey(11));
        EndpointId id = EndpointId.Companion.fromString(hostId);
        EndpointAddr target = new EndpointAddr(id, relay, directs);
        System.out.println("[spike] client dialing " + hostId);
        Connection conn = KtBridge.connect(client, target, ALPN);
        System.out.println("[spike] CLIENT connected, remoteId=" + conn.remoteId().toString());

        BiStream bi = KtBridge.openBi(conn);
        byte[] msg = "hello from client".getBytes();
        KtBridge.writeAll(bi.send(), msg);
        KtBridge.finish(bi.send());
        System.out.println("[spike] CLIENT sent: " + new String(msg));
        System.out.println("[spike] CLIENT paths: " + describePaths(conn));

        Thread.sleep(3000);
        System.out.println("[spike] DONE");
        host.close();
        client.close();
    }

    static String describePaths(Connection conn) {
        StringBuilder sb = new StringBuilder();
        for (PathSnapshot p : conn.paths()) {
            sb.append("{addr=").append(p.getRemoteAddr())
              .append(", relay=").append(p.isRelay())
              .append(", selected=").append(p.isSelected())
              .append(", ip=").append(p.isIp())
              .append("} ");
        }
        return sb.toString();
    }
}
