package dev.belikhun.luna.core.mc.placeholder;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.LongArgumentType;
import dev.belikhun.luna.core.api.placeholder.PlaceholderInspector;
import dev.belikhun.luna.core.api.placeholder.PlaceholderTransportInspector;
import dev.belikhun.luna.core.api.profile.PermissionService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /lunacore placeholder parse|resolve|list} on the mod loaders, the same
 * three reports the Paper command and the proxy command print.
 *
 * The console may always run it; a player needs {@code lunacore.admin}. The
 * check goes through the permission service rather than the vanilla operator
 * level, whose accessor differs between game versions.
 */
public final class PlaceholderDebugCommands {
	private static final String PERMISSION = "lunacore.admin";

	private PlaceholderDebugCommands() {
	}

	/**
	 * Registers the command tree.
	 *
	 * @param dispatcher the server's dispatcher
	 * @param module     resolves to the live module, or null before the server started
	 * @param transport  resolves to the installed transport (the tab bridge), or null
	 */
	public static void register(
		CommandDispatcher<CommandSourceStack> dispatcher,
		Supplier<MinecraftPlaceholderModule> module,
		Supplier<PlaceholderTransportInspector> transport
	) {
		dispatcher.register(Commands.literal("lunacore")
			.requires(source -> mayUse(source, module.get()))
			.then(Commands.literal("placeholder")
				.then(Commands.literal("bridge")
					.executes(context -> bridgeSummary(context, transport))
					.then(Commands.argument("player", StringArgumentType.word())
						.executes(context -> bridge(context, transport))))
				.then(Commands.literal("trace")
					.then(Commands.argument("player", StringArgumentType.word())
						.then(Commands.argument("seconds", LongArgumentType.longArg(1L, 600L))
							.executes(context -> trace(context, transport)))))
				.then(Commands.literal("list")
					.executes(context -> list(context, module, null))
					.then(Commands.argument("namespace", StringArgumentType.word())
						.executes(context -> list(context, module, StringArgumentType.getString(context, "namespace")))))
				.then(Commands.literal("parse")
					.then(Commands.argument("text", StringArgumentType.greedyString())
						.executes(context -> parse(context, module))))
				.then(Commands.literal("resolve")
					.then(Commands.argument("player", StringArgumentType.word())
						.then(Commands.argument("text", StringArgumentType.greedyString())
							.executes(context -> resolve(context, module)))))));
	}

	private static boolean mayUse(CommandSourceStack source, MinecraftPlaceholderModule module) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			return true;
		}

		PermissionService permissions = module == null ? null : module.permissions();
		return permissions != null && permissions.isAvailable() && permissions.hasPermission(player.getUUID(), PERMISSION);
	}

	private static int list(CommandContext<CommandSourceStack> context, Supplier<MinecraftPlaceholderModule> module, String namespace) {
		PlaceholderInspector<ServerPlayer> inspector = inspector(context, module);

		if (inspector == null) {
			return 0;
		}

		send(context, inspector.list(namespace));
		return 1;
	}

	private static int parse(CommandContext<CommandSourceStack> context, Supplier<MinecraftPlaceholderModule> module) {
		PlaceholderInspector<ServerPlayer> inspector = inspector(context, module);

		if (inspector == null) {
			return 0;
		}

		send(context, inspector.parse(StringArgumentType.getString(context, "text")));
		return 1;
	}

	private static int resolve(CommandContext<CommandSourceStack> context, Supplier<MinecraftPlaceholderModule> module) {
		PlaceholderInspector<ServerPlayer> inspector = inspector(context, module);

		if (inspector == null) {
			return 0;
		}

		String reference = StringArgumentType.getString(context, "player");
		ServerPlayer target = null;

		if (reference.equalsIgnoreCase("me") && context.getSource().getEntity() instanceof ServerPlayer self) {
			target = self;
		} else if (!reference.equals("-")) {
			target = context.getSource().getServer().getPlayerList().getPlayerByName(reference);

			if (target == null) {
				context.getSource().sendFailure(Component.literal("Không tìm thấy người chơi đang online: " + reference));
				return 0;
			}
		}

		String label = target == null ? "no player" : target.getName().getString();
		send(context, inspector.resolve(StringArgumentType.getString(context, "text"), target, label));
		return 1;
	}

	private static int bridgeSummary(CommandContext<CommandSourceStack> context, Supplier<PlaceholderTransportInspector> transport) {
		PlaceholderTransportInspector inspector = transport == null ? null : transport.get();

		if (inspector == null) {
			context.getSource().sendFailure(Component.literal("Không có tab bridge trên máy chủ này."));
			return 0;
		}

		send(context, inspector.summary());
		return 1;
	}

	private static int bridge(CommandContext<CommandSourceStack> context, Supplier<PlaceholderTransportInspector> transport) {
		PlaceholderTransportInspector inspector = transport == null ? null : transport.get();

		if (inspector == null) {
			context.getSource().sendFailure(Component.literal("Không có tab bridge trên máy chủ này."));
			return 0;
		}

		String reference = StringArgumentType.getString(context, "player");
		ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(reference);

		if (target == null) {
			context.getSource().sendFailure(Component.literal("Không tìm thấy người chơi đang online: " + reference));
			return 0;
		}

		send(context, inspector.describe(target.getUUID(), target.getName().getString()));
		return 1;
	}

	private static int trace(CommandContext<CommandSourceStack> context, Supplier<PlaceholderTransportInspector> transport) {
		PlaceholderTransportInspector inspector = transport == null ? null : transport.get();

		if (inspector == null) {
			context.getSource().sendFailure(Component.literal("Không có tab bridge trên máy chủ này."));
			return 0;
		}

		String reference = StringArgumentType.getString(context, "player");
		ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(reference);

		if (target == null) {
			context.getSource().sendFailure(Component.literal("Không tìm thấy người chơi đang online: " + reference));
			return 0;
		}

		long seconds = LongArgumentType.getLong(context, "seconds");

		if (!inspector.trace(target.getUUID(), seconds)) {
			context.getSource().sendFailure(Component.literal("Tab bridge chưa có trạng thái cho " + reference + "."));
			return 0;
		}

		context.getSource().sendSuccess(() -> Component.literal("Đang ghi log mọi frame tab bridge của " + target.getName().getString() + " trong " + seconds + "s."), false);
		return 1;
	}

	private static PlaceholderInspector<ServerPlayer> inspector(CommandContext<CommandSourceStack> context, Supplier<MinecraftPlaceholderModule> module) {
		MinecraftPlaceholderModule live = module.get();

		if (live == null) {
			context.getSource().sendFailure(Component.literal("Placeholder engine chưa sẵn sàng."));
			return null;
		}

		return live.inspector();
	}

	private static void send(CommandContext<CommandSourceStack> context, List<String> lines) {
		for (String line : lines) {
			context.getSource().sendSuccess(() -> Component.literal(line), false);
		}
	}
}
