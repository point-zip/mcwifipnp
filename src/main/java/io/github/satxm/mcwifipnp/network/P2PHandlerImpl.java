package io.github.satxm.mcwifipnp.network;

import org.jspecify.annotations.Nullable;

import io.github.satxm.mcwifipnp.MCWiFiPnPUnit;
import io.github.satxm.mcwifipnp.p2p.P2PHandler;
import io.github.satxm.mcwifipnp.p2p.P2PMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/**
 * Adapter between the version-agnostic {@link P2PManager} core and the game:
 * routes control messages through the {@link P2PSender}, shows chat/system
 * messages, and (member side) disconnects the frp connection to reconnect to the
 * local P2P proxy.
 */
public final class P2PHandlerImpl implements P2PHandler {

	private final P2PSender sender;
	private final Minecraft minecraft;

	public P2PHandlerImpl(P2PSender sender) {
		this.sender = sender;
		this.minecraft = Minecraft.getInstance();
	}

	@Override
	public void sendToClient(String playerName, P2PMessage message) {
		this.sender.sendToClient(playerName, P2PPayload.of(message));
	}

	@Override
	public void sendToServer(P2PMessage message) {
		this.sender.sendToServer(P2PPayload.of(message));
	}

	@Override
	public void requestReconnect(String host, int port) {
		// Member side: drop the frp connection and reconnect through the local P2P
		// proxy, which forwards to the host over the iroh stream.
		MCWiFiPnPUnit.LOGGER.info("P2P: reconnecting the game client to {}:{}", host, port);
		Connection connection = this.minecraft.getConnection().getConnection();
		if (connection != null) {
			connection.disconnect(Component.translatable("mcwifipnp.p2p.switching"));
		}
		ServerAddress address = new ServerAddress(host, port);
		ServerData serverData = new ServerData("P2P", host + ":" + port, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(this.minecraft.gui.screen(), this.minecraft, address, serverData, false,
				new TransferState(java.util.Map.of(), java.util.Map.of(), false));
	}

	@Override
	public void notify(String message) {
		// Show locally without round-tripping through the network; works on both
		// the host (singleplayer server) and the member side.
		this.minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable(message));
	}

	/** Current game connection (used by the member-side reconnect logic). */
	@Nullable
	public Connection getGameConnection() {
		if (this.minecraft.getConnection() == null) {
			return null;
		}
		return this.minecraft.getConnection().getConnection();
	}
}
