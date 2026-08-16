package io.github.satxm.mcwifipnp.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import io.github.satxm.mcwifipnp.Config;
import io.github.satxm.mcwifipnp.p2p.P2PManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Host-side P2P command, registered as /p2phost so it never collides with the
 * member-side /p2p client command tree (an OP'd member would otherwise hit the
 * server command instead of their own, and could even change the host's config).
 *
 * /p2phost enable|disable|token &lt;value|none&gt;|status
 */
public class P2PCommand {
	public static void register(CommandDispatcher<CommandSourceStack> commandDispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> cmdBuilder = Commands.literal("p2phost")
			.requires(Commands.hasPermission(Commands.LEVEL_OWNERS));

		cmdBuilder = cmdBuilder
			.then(Commands.literal("enable")
					.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(P2PCommand::setEnabled)))
			.then(Commands.literal("token")
					.then(Commands.argument("value", StringArgumentType.greedyString()).executes(P2PCommand::setToken)))
			.then(Commands.literal("status").executes(P2PCommand::showStatus));

		commandDispatcher.register(cmdBuilder);
	}

	private static int setEnabled(CommandContext<CommandSourceStack> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		MinecraftServer server = context.getSource().getServer();
		Config cfg = Config.readFromPublishedServer(server);

		if (cfg.enableP2P ^ enabled) {
			cfg.enableP2P = enabled;
			cfg.saveAndApply(server);
			P2PManager.getInstance().setEnabled(enabled);
			if (enabled && server.isPublished()) {
				P2PManager.getInstance().setToken(cfg.p2pToken);
				P2PManager.getInstance().setTokenRequired(cfg.p2pToken != null);
				P2PManager.getInstance().setAutoSwitch(cfg.p2pAutoSwitch);
				P2PManager.getInstance().startHost(server.getPort());
			}
		}

		return showStatus(context);
	}

	private static int setToken(CommandContext<CommandSourceStack> context) {
		String value = StringArgumentType.getString(context, "value").trim();
		MinecraftServer server = context.getSource().getServer();
		Config cfg = Config.readFromPublishedServer(server);

		if ("none".equalsIgnoreCase(value)) {
			cfg.p2pToken = null;
		} else {
			if (value.length() < 4 || value.length() > 64) {
				context.getSource().sendFailure(Component.translatable("mcwifipnp.p2p.token_invalid"));
				return 0;
			}
			cfg.p2pToken = value;
		}
		cfg.saveAndApply(server);

		P2PManager manager = P2PManager.getInstance();
		manager.setToken(cfg.p2pToken);
		manager.setTokenRequired(cfg.p2pToken != null);
		// The member may have a pending offer waiting for a token.
		manager.onTokenProvided();

		context.getSource().sendSuccess(() -> Component.translatable("mcwifipnp.p2p.token_set",
				cfg.p2pToken == null ? Component.translatable("mcwifipnp.p2p.token_none")
						: Component.literal(cfg.p2pToken)), false);
		return 1;
	}

	private static int showStatus(CommandContext<CommandSourceStack> context) {
		MinecraftServer server = context.getSource().getServer();
		Config cfg = Config.readFromPublishedServer(server);

		Component status = Component.literal("P2P: ")
			.append(cfg.enableP2P ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF)
			.append(Component.literal("  token: "))
			.append(cfg.p2pToken == null ? Component.translatable("mcwifipnp.p2p.token_none")
					: Component.literal(cfg.p2pToken));
		context.getSource().sendSuccess(() -> status, false);
		return 1;
	}
}
