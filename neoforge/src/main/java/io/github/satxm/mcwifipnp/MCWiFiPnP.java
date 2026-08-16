package io.github.satxm.mcwifipnp;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import io.github.satxm.mcwifipnp.commands.P2PClientCommand;
import io.github.satxm.mcwifipnp.network.P2PHandlerImpl;
import io.github.satxm.mcwifipnp.network.P2PPayload;
import io.github.satxm.mcwifipnp.network.P2PSender;
import io.github.satxm.mcwifipnp.p2p.P2PManager;

@Mod(MCWiFiPnPUnit.MODID)
public class MCWiFiPnP {
	public MCWiFiPnP(IEventBus modEventBus) {
		NeoForge.EVENT_BUS.register(this);
		modEventBus.addListener(this::onRegisterPayloadHandlers);
		NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
		NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
	}

	/** When a player joins (over the frp path), ask them about a direct connection. */
	private void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
			P2PManager.getInstance().onMemberJoined(serverPlayer.getGameProfile().name());
		}
	}

	/** Member-side P2P commands (allow/deny/token), no OP required. */
	private void onRegisterClientCommands(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
		P2PClientCommand.register(event.getDispatcher());
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		MCWiFiPnPUnit.registerCommands(event.getDispatcher(), FMLEnvironment.getDist().isDedicatedServer());
	}

	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event) {
		MCWiFiPnPUnit.onServerStarting(event.getServer());
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		MCWiFiPnPUnit.onServerStopping(event.getServer());
	}

	/** Register the P2P payload on the mod bus; one bidirectional handler for both roles. */
	private void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
		event.registrar(MCWiFiPnPUnit.MODID).playBidirectional(P2PPayload.TYPE, P2PPayload.STREAM_CODEC,
				(payload, context) -> handlePayload(payload, context));

		// The shared handler sends messages; P2PManager is initialized lazily on first use.
		P2PManager.getInstance().setHandler(new P2PHandlerImpl(new P2PSender() {
			@Override
			public void sendToClient(String playerName, P2PPayload p2pPayload) {
				var server = Minecraft.getInstance().getSingleplayerServer();
				if (server == null) {
					return;
				}
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					if (player.getGameProfile().name().equals(playerName)) {
						PacketDistributor.sendToPlayer(player, p2pPayload);
						return;
					}
				}
			}

			@Override
			public void sendToServer(P2PPayload p2pPayload) {
				var connection = Minecraft.getInstance().getConnection();
				if (connection != null) {
					connection.getConnection().send(new ServerboundCustomPayloadPacket(p2pPayload));
				}
			}
		}));
	}

	private static void handlePayload(P2PPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (context.flow() == PacketFlow.SERVERBOUND) {
				var player = context.player();
				if (player instanceof ServerPlayer serverPlayer) {
					P2PManager.getInstance()
							.handleServerbound(serverPlayer.getGameProfile().name(), payload.asMessage());
				}
			} else {
				P2PManager.getInstance().handleClientbound(payload.asMessage());
			}
		}).exceptionally(e -> {
			context.disconnect(Component.translatable("mcwifipnp.p2p.network_error"));
			return null;
		});
	}
}
