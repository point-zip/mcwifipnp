package io.github.satxm.mcwifipnp.network;

import io.github.satxm.mcwifipnp.p2p.P2PMessage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The custom payload that carries a {@link P2PMessage} over the vanilla network
 * channel "mcwifipnp:p2p". Used in both directions (host &lt;-&gt; member) while the
 * game connection itself still runs over frp.
 */
public record P2PPayload(byte[] data) implements CustomPacketPayload {

	public static final Identifier ID = Identifier.fromNamespaceAndPath("mcwifipnp", "p2p");

	public static final Type<P2PPayload> TYPE = new Type<P2PPayload>(ID);

	public static final StreamCodec<FriendlyByteBuf, P2PPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data()),
			buf -> {
				byte[] data = new byte[buf.readableBytes()];
				buf.readBytes(data);
				return new P2PPayload(data);
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Decode the wrapped control message. */
	public P2PMessage asMessage() {
		return P2PMessage.decode(this.data);
	}

	/** Wrap a control message. */
	public static P2PPayload of(P2PMessage message) {
		return new P2PPayload(message.encode());
	}
}
