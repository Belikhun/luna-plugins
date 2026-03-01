package dev.belikhun.luna.countdown.commands;

import java.util.Arrays;
import java.util.List;

import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.countdown.CountInstance;
import dev.belikhun.luna.countdown.Countdown;
import dev.belikhun.luna.countdown.CountInstance.CountdownCallback;

import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;

public class ShutdownCommand implements TabExecutor {
	public static CountInstance instance;
	
	public ShutdownCommand() { }

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("countdown.shutdown") && !(sender instanceof ConsoleCommandSender))
			return false;

		if (args.length < 1) {
			sender.sendMessage(Countdown.mm(CommandStrings.usage("/shutdown",
				CommandStrings.required("length", "time"),
				CommandStrings.optional("message", "text"))));
			return true;
		}

		if (args[0].equalsIgnoreCase("cancel")) {
			if (instance == null) {
				sender.sendMessage(Countdown.mm("<red>❌ Không có lịch tắt máy chủ.</red>"));
				return true;
			}

			instance.stop("<green><bold>Đã Hủy Tắt Máy Chủ!</bold></green>");
			instance.bar.setColor(BarColor.GREEN);
			Countdown.broadcast("<green>✔ Đã hủy tắt máy chủ.</green>");
			instance = null;
			return true;
		}

		if (instance != null) {
			sender.sendMessage(Countdown.mm("<red>❌ Tắt máy chủ đã được lên lịch!</red> <white>Hủy bằng <yellow>/shutdown cancel</yellow>.</white>"));
			return true;
		}

		String message = null;
		int length = Countdown.parseTime(args[0]);
		if (length <= 0) {
			sender.sendMessage(Countdown.mm("<red>❌ Thời gian không hợp lệ: <white>" + Countdown.escape(args[0]) + "</white></red>"));
			return true;
		}

		if (args.length >= 2)
			message = StringUtils.join(Arrays.copyOfRange(args, 1, args.length), ' ');
		
		start(message, length);
		return true;
	}

	public void start(String reason, int seconds) {
		if (instance != null)
			return;

		instance = new CountInstance(seconds, new CountdownCallback() {

			@Override
			public void begin(BossBar bar) {
				String message = (reason != null)
					? "<white>Máy chủ sẽ tắt sau " + CountInstance.readableTime(seconds)
						+ "<white> nữa! <gray>(lí do: " + Countdown.escape(reason) + ")</gray></white>"
					: "<white>Máy chủ sẽ tắt sau " + CountInstance.readableTime(seconds) + "<white> nữa!</white>";
				
				Countdown.broadcast(message);
			}

			@Override
			public void update(BossBar bar, double remain) {
				String message = (reason != null)
					? "<color:" + LunaPalette.DANGER_500 + "><bold>⚠⚠⚠ TẮT MÁY CHỦ ⚠⚠⚠</bold></color><white> sau "
						+ CountInstance.readableTime(remain)
						+ " <gray>(" + Countdown.escape(reason) + ")</gray></white>"
					: "<color:" + LunaPalette.DANGER_500 + "><bold>⚠⚠⚠ TẮT MÁY CHỦ ⚠⚠⚠</bold></color><white> sau "
						+ CountInstance.readableTime(remain) + "</white>";

				bar.setTitle(Countdown.legacy(message));
			}

			@Override
			public void complete(BossBar bar) {
				String message = "<yellow><bold>Đang Tắt Máy Chủ...</bold></yellow>";
				Countdown.broadcast("<yellow>⚠ Đang tắt máy chủ...</yellow>");
				bar.setColor(BarColor.YELLOW);
				bar.setTitle(Countdown.legacy(message));
				instance = null;

				Bukkit.getScheduler().runTaskLater(Countdown.instance, Bukkit::shutdown, 20L * 3L);
			}
		});
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
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
}
