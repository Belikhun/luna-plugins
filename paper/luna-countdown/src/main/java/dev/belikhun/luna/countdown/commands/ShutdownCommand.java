package dev.belikhun.luna.countdown.commands;

import java.util.Arrays;
import java.util.List;

import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.countdown.CountdownMessages;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.countdown.CountInstance;
import dev.belikhun.luna.countdown.Countdown;
import dev.belikhun.luna.countdown.CountInstance.CountdownCallback;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.util.Collection;

public class ShutdownCommand implements BasicCommand {
	public static CountInstance instance;

	public ShutdownCommand() { }

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission("countdown.shutdown") && !(sender instanceof ConsoleCommandSender))
			return;

		if (args.length < 1) {
			sender.sendMessage(Countdown.mm(CountdownMessages.shutdownUsage("shutdown")));
			return;
		}

		if (args[0].equalsIgnoreCase("cancel")) {
			if (instance == null) {
				sender.sendMessage(Countdown.mm(CountdownMessages.noShutdownScheduled()));
				return;
			}

			instance.stop("<green><bold>Đã Hủy Tắt Máy Chủ!</bold></green>");
			instance.bar.setColor(BarColor.GREEN);
			Countdown.broadcast(CountdownMessages.shutdownCancelled());
			instance = null;
			return;
		}

		if (instance != null) {
			sender.sendMessage(Countdown.mm(CountdownMessages.shutdownAlreadyScheduled()));
			return;
		}

		String message = null;
		int length = Countdown.parseTime(args[0]);
		if (length <= 0) {
			sender.sendMessage(Countdown.mm(CountdownMessages.invalidTime(args[0])));
			return;
		}

		if (args.length >= 2)
			message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

		start(message, length);
	}

	public void start(String reason, int seconds) {
		if (instance != null)
			return;

		instance = new CountInstance(seconds, new CountdownCallback() {

			@Override
			public void begin(BossBar bar) {
				Countdown.broadcast(CountdownMessages.shutdownBegin(seconds, reason));
			}

			@Override
			public void update(BossBar bar, double remain) {
				bar.setTitle(Countdown.legacy(CountdownMessages.shutdownBar(remain, reason)));
			}

			@Override
			public void complete(BossBar bar) {
				String message = "<yellow><bold>Đang Tắt Máy Chủ...</bold></yellow>";
				Countdown.broadcast(CountdownMessages.shutdownNow());
				bar.setColor(BarColor.YELLOW);
				bar.setTitle(Countdown.legacy(message));
				instance = null;

				Bukkit.getScheduler().runTaskLater(Countdown.instance, Bukkit::shutdown, 20L * 3L);
			}
		});
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission("countdown.shutdown") && !(sender instanceof ConsoleCommandSender)) {
			return List.of();
		}

		if (args.length == 1) {
			return CommandCompletions.filterPrefix(List.of("cancel", "30", "60", "120", "300", "30s", "1m", "5m", "10m"), args[0]);
		}

		if (args.length >= 2 && !"cancel".equalsIgnoreCase(args[0])) {
			return CommandCompletions.filterPrefix(List.of("Bảo_trì", "Khởi_động_lại", "Cập_nhật_hệ_thống"), args[args.length - 1]);
		}

		return List.of();
	}

	@Override
	public String permission() {
		return "countdown.shutdown";
	}
}

