package dev.belikhun.luna.core.paper.help;

import dev.belikhun.luna.core.paper.LunaCoreServices;
import dev.belikhun.luna.core.api.help.HelpCategory;
import dev.belikhun.luna.core.api.help.HelpEntry;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class HelpBasicCommand implements BasicCommand {
	private final LunaCoreServices services;
	private final HelpCommandListener listener;
	private final MiniMessage miniMessage;

	public HelpBasicCommand(LunaCoreServices services, HelpCommandListener listener) {
		this.services = services;
		this.listener = listener;
		this.miniMessage = MiniMessage.miniMessage();
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!(sender instanceof Player player)) {
			sender.sendMessage(miniMessage.deserialize("<red>❌ Lệnh này chỉ có thể dùng trong game.</red>"));
			return;
		}

		if (args.length == 0) {
			listener.openHelp(player);
			return;
		}

		String target = args[0].trim();
		boolean matched = listener.openHelp(player, target);
		if (!matched) {
			player.sendMessage(miniMessage.deserialize("<yellow>ℹ Không tìm thấy plugin/lệnh khớp tuyệt đối. Đang hiển thị kết quả gần nhất.</yellow>"));
		}
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		if (args.length > 1) {
			return java.util.List.of();
		}

		CommandSender sender = source.getSender();
		LinkedHashSet<String> values = new LinkedHashSet<>();
		for (HelpCategory category : services.helpRegistry().visibleCategories(sender)) {
			values.add(category.id());
			values.add(category.plugin());
			values.add(category.title());
		}
		for (HelpEntry entry : services.helpRegistry().visibleEntries(sender)) {
			values.add(entry.command());
			if (entry.command().startsWith("/")) {
				values.add(entry.command().substring(1));
			}
		}

		String remaining = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
		ArrayList<String> filtered = new ArrayList<>();
		for (String value : values) {
			if (remaining.isBlank() || value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
				filtered.add(value);
			}
		}
		filtered.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
		return filtered;
	}

	@Override
	public String permission() {
		return null;
	}
}

