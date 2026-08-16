package io.github.satxm.mcwifipnp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import io.github.satxm.mcwifipnp.network.P2PHandlerImpl;
import io.github.satxm.mcwifipnp.network.P2PPayload;
import io.github.satxm.mcwifipnp.network.P2PSender;
import io.github.satxm.mcwifipnp.p2p.P2PManager;

public class MCWiFiPnP implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer {
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STOPPING.register(MCWiFiPnPUnit::onServerStopping);
		ServerLifecycleEvents.SERVER_STARTING.register(MCWiFiPnPUnit::onServerStarting);

		// P2P: receive member-to-host control messages (works on the host's
		// integrated server and on dedicated servers alike).
		PayloadTypeRegistry.serverboundPlay().register(P2PPayload.TYPE, P2PPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(P2PPayload.TYPE, (payload, context) -> {
			context.player().level().getServer().execute(() -> {
				P2PManager.getInstance()
						.handleServerbound(context.player().getGameProfile().name(), payload.asMessage());
			});
		});
	}

	@Override
	public void onInitializeClient() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			MCWiFiPnPUnit.registerCommands(dispatcher, false);
		});

		// P2P: receive host-to-member control messages, and set the shared handler
		// that both roles (host and member) use to send messages.
		PayloadTypeRegistry.clientboundPlay().register(P2PPayload.TYPE, P2PPayload.STREAM_CODEC);
		ClientPlayNetworking.registerGlobalReceiver(P2PPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> P2PManager.getInstance().handleClientbound(payload.asMessage()));
		});

		P2PManager.getInstance().setHandler(new P2PHandlerImpl(new P2PSender() {
			@Override
			public void sendToClient(String playerName, P2PPayload p2pPayload) {
				var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
				if (server == null) {
					return;
				}
				for (var player : server.getPlayerList().getPlayers()) {
					if (player.getGameProfile().name().equals(playerName)) {
						ServerPlayNetworking.send(player, p2pPayload);
						return;
					}
				}
			}

			@Override
			public void sendToServer(P2PPayload p2pPayload) {
				try {
					ClientPlayNetworking.send(p2pPayload);
				} catch (IllegalStateException e) {
					// not connected to a server; ignore
				}
			}
		}));
	}

	@Override
	public void onInitializeServer() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			MCWiFiPnPUnit.registerCommands(dispatcher, true);
		});
	}
}
