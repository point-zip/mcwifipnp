package io.github.satxm.mcwifipnp.p2p;

/**
 * Callbacks implemented by the platform adapter layer. This interface stays free
 * of Minecraft types so the P2P core can be shared across version branches; the
 * adapter translates these into packet sends, reconnects and chat messages.
 */
public interface P2PHandler {

	/** host side: send a control message to the named member. */
	void sendToClient(String playerName, P2PMessage message);

	/** member side: send a control message to the host. */
	void sendToServer(P2PMessage message);

	/** member side: disconnect the current (frp) connection and connect to host:port instead. */
	void requestReconnect(String host, int port);

	/** Show a chat/system message to the local player. */
	void notify(String message);
}
