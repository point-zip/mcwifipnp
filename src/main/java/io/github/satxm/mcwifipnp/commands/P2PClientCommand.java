package io.github.satxm.mcwifipnp.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import io.github.satxm.mcwifipnp.p2p.P2PManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Member-side P2P commands, registered on the client command dispatcher so they
 * work without OP permissions while connected to a server:
 *
 * <ul>
 *   <li>/p2p allow - accept the host's direct-connection offer</li>
 *   <li>/p2p deny - decline it (stay on the frp path)</li>
 *   <li>/p2p token &lt;value&gt; - provide the token the host requires</li>
 * </ul>
 */
public final class P2PClientCommand {

	private P2PClientCommand() {
	}

	/** Register on a client command dispatcher (NeoForge uses CommandSourceStack). */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("p2p")
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
							P2PManager.getInstance().setToken(StringArgumentType.getString(ctx, "value"));
							P2PManager.getInstance().onTokenProvided();
							return 1;
						}))));
	}
}
