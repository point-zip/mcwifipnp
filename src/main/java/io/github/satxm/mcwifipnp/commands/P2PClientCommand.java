package io.github.satxm.mcwifipnp.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import io.github.satxm.mcwifipnp.p2p.P2PManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Member-side P2P commands, registered on the client command dispatcher so they
 * work without OP permissions while connected to a server:
 *
 * <ul>
 *   <li>/p2p enable &lt;true|false&gt; - turn P2P on/off; enabling auto-requests a direct connection</li>
 *   <li>/p2p connect - manually request a direct connection now</li>
 *   <li>/p2p allow - accept the host's direct-connection offer</li>
 *   <li>/p2p deny - decline it (stay on the frp path)</li>
 *   <li>/p2p token &lt;value&gt; - provide the token the host requires (also enables P2P)</li>
 *   <li>/p2p status - show member-side P2P state</li>
 * </ul>
 */
public final class P2PClientCommand {

	private P2PClientCommand() {
	}

	/** Register on a client command dispatcher (NeoForge uses CommandSourceStack). */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("p2p")
				.then(Commands.literal("enable")
						.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
							P2PManager manager = P2PManager.getInstance();
							manager.setMemberEnabled(BoolArgumentType.getBool(ctx, "enabled"));
							if (manager.isMemberEnabled()) {
								manager.onMemberConnectRequested();
							}
							ctx.getSource().sendSuccess(() -> memberStatus(), false);
							return 1;
						})))
				.then(Commands.literal("connect").executes(ctx -> {
					P2PManager manager = P2PManager.getInstance();
					manager.setMemberEnabled(true);
					manager.onMemberConnectRequested();
					return 1;
				}))
				.then(Commands.literal("allow").executes(ctx -> {
					P2PManager.getInstance().onConsentAccepted();
					return 1;
				}))
				.then(Commands.literal("deny").executes(ctx -> {
					P2PManager.getInstance().onConsentDenied();
					return 1;
				}))
				.then(Commands.literal("token")
						.then(Commands.argument("value", StringArgumentType.greedyString()).executes(ctx -> {
							P2PManager manager = P2PManager.getInstance();
							manager.setToken(StringArgumentType.getString(ctx, "value"));
							manager.setMemberEnabled(true);
							manager.onTokenProvided();
							manager.onMemberConnectRequested();
							return 1;
						})))
				.then(Commands.literal("relay")
						.then(Commands.argument("url", StringArgumentType.greedyString()).executes(ctx -> {
							P2PManager manager = P2PManager.getInstance();
							String url = StringArgumentType.getString(ctx, "url").trim();
							manager.setRelayUrl("none".equalsIgnoreCase(url) ? null : url);
							manager.onMemberConnectRequested();
							return 1;
						})))
				.then(Commands.literal("status").executes(ctx -> {
					ctx.getSource().sendSuccess(() -> memberStatus(), false);
					return 1;
				})));
	}

	private static Component memberStatus() {
		P2PManager manager = P2PManager.getInstance();
		return Component.literal("P2P: ")
				.append(manager.isMemberEnabled() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}
}
