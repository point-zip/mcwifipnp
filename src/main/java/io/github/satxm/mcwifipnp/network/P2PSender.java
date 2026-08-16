package io.github.satxm.mcwifipnp.network;

/**
 * Platform-specific payload transport. Each loader entry point provides an
 * implementation wired to its networking API (Fabric networking v1, NeoForge
 * PacketDistributor). Keeps {@code P2PHandlerImpl} free of loader-specific calls.
 */
public interface P2PSender {
	/** host side: send the payload to a connected member. */
	void sendToClient(String playerName, P2PPayload payload);

	/** member side: send the payload to the host. */
	void sendToServer(P2PPayload payload);
}
