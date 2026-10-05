package dev.belikhun.luna.core.paper.command;

import dev.belikhun.luna.core.paper.LunaCorePlugin;
import dev.belikhun.luna.core.api.placeholder.PlaceholderInspector;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class LunaCoreCommand implements BasicCommand {
	private static final String PERMISSION = "lunacore.admin";

	private final LunaCorePlugin plugin;
	private final PlaceholderInspector<OfflinePlayer> inspector;

	public LunaCoreCommand(LunaCorePlugin plugin, PlaceholderInspector<OfflinePlayer> inspector) {
		this.plugin = plugin;
		this.inspector = inspector;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission(PERMISSION)) {
			sender.sendRichMessage("<red>❌ Bạn không có quyền dùng lệnh này.</red>");
			return;
		}

		if (args.length == 0 || args[0].equalsIgnoreCase("reload")) {
			plugin.reloadCoreModules();
			sender.sendRichMessage("<green>✔ Đã reload LunaCore config và module liên quan.</green>");
			return;
		}

		if (args[0].equalsIgnoreCase("placeholder")) {
			executePlaceholder(sender, Arrays.copyOfRange(args, 1, args.length));
			return;
		}

		sendUsage(sender);
	}

	private void sendUsage(CommandSender sender) {
		sender.sendRichMessage(CommandStrings.usage("/lunacorepaper", CommandStrings.literal("reload")));
		sender.sendRichMessage(CommandStrings.usage("/lunacorepaper", CommandStrings.literal("placeholder"), CommandStrings.literal("parse"), CommandStrings.required("text", "text")));
		sender.sendRichMessage(CommandStrings.usage("/lunacorepaper", CommandStrings.literal("placeholder"), CommandStrings.literal("resolve"), CommandStrings.required("player|-", "text"), CommandStrings.required("text", "text")));
		sender.sendRichMessage(CommandStrings.usage("/lunacorepaper", CommandStrings.literal("placeholder"), CommandStrings.literal("list"), CommandStrings.optional("namespace", "text")));
	}

	private void executePlaceholder(CommandSender sender, String[] args) {
		if (args.length == 0) {
			sendUsage(sender);
			return;
		}

		String action = args[0].toLowerCase(java.util.Locale.ROOT);

		if (action.equals("list")) {
			sendLines(sender, inspector.list(args.length > 1 ? args[1] : null));
			return;
		}

		if (action.equals("parse")) {
			if (args.length < 2) {
				sendUsage(sender);
				return;
			}

			sendLines(sender, inspector.parse(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
			return;
		}

		if (action.equals("resolve")) {
			if (args.length < 3) {
				sendUsage(sender);
				return;
			}

			OfflinePlayer target = resolveTarget(sender, args[1]);
			String label = target == null ? "no player" : (target.getName() == null ? target.getUniqueId().toString() : target.getName());
			sendLines(sender, inspector.resolve(String.join(" ", Arrays.copyOfRange(args, 2, args.length)), target, label));
			return;
		}

		sendUsage(sender);
	}

	private OfflinePlayer resolveTarget(CommandSender sender, String reference) {
		if (reference.equals("-")) {
			return null;
		}

		if (reference.equalsIgnoreCase("me") && sender instanceof Player self) {
			return self;
		}

		Player online = Bukkit.getPlayerExact(reference);

		if (online != null) {
			return online;
		}

		try {
			return Bukkit.getOfflinePlayer(UUID.fromString(reference));
		} catch (IllegalArgumentException exception) {
			return Bukkit.getOfflinePlayer(reference);
		}
	}

	private void sendLines(CommandSender sender, List<String> lines) {
		for (String line : lines) {
			sender.sendMessage(Component.text(line));
		}
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission(PERMISSION)) {
			return List.of();
		}

		if (args.length == 0) {
			return List.of("reload", "placeholder");
		}

		if (args.length == 1) {
			return CommandCompletions.filterPrefix(List.of("reload", "placeholder"), args[0]);
		}

		if (!args[0].equalsIgnoreCase("placeholder")) {
			return List.of();
		}

		if (args.length == 2) {
			return CommandCompletions.filterPrefix(List.of("parse", "resolve", "list"), args[1]);
		}

		if (args.length == 3 && args[1].equalsIgnoreCase("resolve")) {
			List<String> names = new ArrayList<>(List.of("-", "me"));
			Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
			return CommandCompletions.filterPrefix(names, args[2]);
		}

		return List.of();
	}

	@Override
	public String permission() {
		return PERMISSION;
	}
}

