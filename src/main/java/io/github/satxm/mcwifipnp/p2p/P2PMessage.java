package io.github.satxm.mcwifipnp.p2p;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * P2P control message wire format, independent of any Minecraft API so it can be
 * reused across version branches.
 *
 * Layout (all multi-byte values big-endian):
 * <pre>
 *   magic   : 4 bytes  "MCP2"
 *   version : 1 byte    protocol version (currently 1)
 *   type    : 1 byte    see TYPE_* constants
 *   payload : type-specific (see {@link P2PMessage})
 * </pre>
 */
public final class P2PMessage {

	/** Magic bytes identifying an mcwifipnp P2P control message. */
	public static final byte[] MAGIC = { 'M', 'C', 'P', '2' };
	/** Current protocol version. */
	public static final int VERSION = 1;

	/** host -&gt; member: P2P is available, member may try hole punching. */
	public static final int TYPE_OFFER = 1;
	/** member -&gt; host: here is my iroh endpoint (and token if required). */
	public static final int TYPE_HELLO = 2;
	/** host -&gt; member: here is my iroh endpoint, go ahead. */
	public static final int TYPE_ACCEPT = 3;
	/** host -&gt; member: token rejected, stay on the frp path. */
	public static final int TYPE_DENY = 4;
	/** member -&gt; host: direct connection established, host may open the tunnel. */
	public static final int TYPE_TUNNEL_READY = 5;
	/** host -&gt; member: reconnect to 127.0.0.1:proxyPort. */
	public static final int TYPE_SWITCH_READY = 6;
	/** host -&gt; member: ask whether the member wants a direct P2P connection. */
	public static final int TYPE_CONSENT_REQUEST = 7;
	/** member -&gt; host: yes, proceed with hole punching. */
	public static final int TYPE_CONSENT_ACCEPT = 8;
	/** member -&gt; host: I want a direct connection, start hole punching. */
	public static final int TYPE_REQUEST_HOLEPUNCH = 9;

	private final int type;
	/** offer: whether a token is required. */
	private final boolean tokenRequired;
	/** hello: the token supplied by the member (null when not required). */
	private final String token;
	/** hello/accept: serialized iroh EndpointAddr of the sender. */
	private final String endpointAddr;
	/** switch-ready: the member-side local proxy port. */
	private final int proxyPort;

	private P2PMessage(int type, boolean tokenRequired, String token, String endpointAddr, int proxyPort) {
		this.type = type;
		this.tokenRequired = tokenRequired;
		this.token = token;
		this.endpointAddr = endpointAddr;
		this.proxyPort = proxyPort;
	}

	public static P2PMessage offer(boolean tokenRequired) {
		return new P2PMessage(TYPE_OFFER, tokenRequired, null, null, 0);
	}

	public static P2PMessage hello(String token, String endpointAddr) {
		return new P2PMessage(TYPE_HELLO, false, token, endpointAddr, 0);
	}

	public static P2PMessage accept(String endpointAddr) {
		return new P2PMessage(TYPE_ACCEPT, false, null, endpointAddr, 0);
	}

	public static P2PMessage deny() {
		return new P2PMessage(TYPE_DENY, false, null, null, 0);
	}

	public static P2PMessage tunnelReady() {
		return new P2PMessage(TYPE_TUNNEL_READY, false, null, null, 0);
	}

	public static P2PMessage switchReady(int proxyPort) {
		return new P2PMessage(TYPE_SWITCH_READY, false, null, null, proxyPort);
	}

	public static P2PMessage consentRequest() {
		return new P2PMessage(TYPE_CONSENT_REQUEST, false, null, null, 0);
	}

	public static P2PMessage consentAccept() {
		return new P2PMessage(TYPE_CONSENT_ACCEPT, false, null, null, 0);
	}

	public static P2PMessage requestHolepunch() {
		return new P2PMessage(TYPE_REQUEST_HOLEPUNCH, false, null, null, 0);
	}

	public int getType() {
		return this.type;
	}

	public boolean isTokenRequired() {
		return this.tokenRequired;
	}

	public String getToken() {
		return this.token;
	}

	public String getEndpointAddr() {
		return this.endpointAddr;
	}

	public int getProxyPort() {
		return this.proxyPort;
	}

	/** Encode this message to the wire format. */
	public byte[] encode() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.write(MAGIC);
			out.writeByte(VERSION);
			out.writeByte(this.type);
			switch (this.type) {
				case TYPE_OFFER:
					out.writeBoolean(this.tokenRequired);
					break;
				case TYPE_HELLO:
					writeNullableString(out, this.token);
					writeString(out, this.endpointAddr);
					break;
				case TYPE_ACCEPT:
					writeString(out, this.endpointAddr);
					break;
				case TYPE_TUNNEL_READY:
				case TYPE_DENY:
				case TYPE_CONSENT_REQUEST:
				case TYPE_CONSENT_ACCEPT:
				case TYPE_REQUEST_HOLEPUNCH:
					break;
				case TYPE_SWITCH_READY:
					out.writeInt(this.proxyPort);
					break;
				default:
					throw new IllegalArgumentException("unknown p2p message type " + this.type);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("failed to encode p2p message", e);
		}
		return bytes.toByteArray();
	}

	/** Decode a message from the wire format. Returns null if the bytes are not a valid message. */
	public static P2PMessage decode(byte[] data) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			byte[] magic = new byte[MAGIC.length];
			in.readFully(magic);
			for (int i = 0; i < MAGIC.length; i++) {
				if (magic[i] != MAGIC[i]) {
					return null;
				}
			}
			int version = in.readUnsignedByte();
			if (version != VERSION) {
				return null;
			}
			int type = in.readUnsignedByte();
			switch (type) {
				case TYPE_OFFER:
					return offer(in.readBoolean());
				case TYPE_HELLO:
					return new P2PMessage(TYPE_HELLO, false, readNullableString(in), readString(in), 0);
				case TYPE_ACCEPT:
					return accept(readString(in));
				case TYPE_TUNNEL_READY:
					return tunnelReady();
				case TYPE_DENY:
					return deny();
				case TYPE_CONSENT_REQUEST:
					return consentRequest();
				case TYPE_CONSENT_ACCEPT:
					return consentAccept();
				case TYPE_REQUEST_HOLEPUNCH:
					return requestHolepunch();
				case TYPE_SWITCH_READY:
					return switchReady(in.readInt());
				default:
					return null;
			}
		} catch (IOException e) {
			return null;
		}
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		byte[] raw = value.getBytes(StandardCharsets.UTF_8);
		out.writeShort(raw.length);
		out.write(raw);
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = in.readUnsignedShort();
		byte[] raw = new byte[length];
		in.readFully(raw);
		return new String(raw, StandardCharsets.UTF_8);
	}

	private static void writeNullableString(DataOutputStream out, String value) throws IOException {
		if (value == null) {
			out.writeBoolean(false);
		} else {
			out.writeBoolean(true);
			writeString(out, value);
		}
	}

	private static String readNullableString(DataInputStream in) throws IOException {
		return in.readBoolean() ? readString(in) : null;
	}

	@Override
	public String toString() {
		return "P2PMessage{type=" + this.type + ", tokenRequired=" + this.tokenRequired + ", token=" + this.token
				+ ", endpointAddr=" + this.endpointAddr + ", proxyPort=" + this.proxyPort + "}";
	}
}
