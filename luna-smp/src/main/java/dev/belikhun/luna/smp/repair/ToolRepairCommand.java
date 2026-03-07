package dev.belikhun.luna.smp.repair;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.List;
import dev.belikhun.luna.core.api.string.MessageFormatter;

public final class ToolRepairCommand implements BasicCommand {
	private final ToolRepairService service;
	private final MessageFormatter formatter;
	private final ToolRepairConfirmGui confirmGui;

	public ToolRepairCommand(ToolRepairService service, MessageFormatter formatter, ToolRepairConfirmGui confirmGui) {
		this.service = service;
		this.formatter = formatter;
		this.confirmGui = confirmGui;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!(sender instanceof Player player)) {
			send(sender, "messages.no-console");
			return;
		}

		if (args.length > 0) {
			send(sender, "messages.repair-usage");
			return;
		}

		if (!player.hasPermission(permission())) {
			send(player, "messages.no-permission");
			return;
		}

		confirmGui.open(player);
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		if (args.length <= 1) {
			return List.of();
		}

		return List.of();
	}

	@Override
	public String permission() {
		return "lunasmp.repair";
	}

	private void send(CommandSender sender, String path) {
		send(sender, path, Map.of());
	}

	private void send(CommandSender sender, String path, Map<String, String> placeholders) {
		String text = service.message(path);
		sender.sendMessage(formatter.format(sender, text, placeholders));
	}
}
