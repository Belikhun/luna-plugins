package dev.belikhun.luna.countdown.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.countdown.CountInstance;
import dev.belikhun.luna.countdown.Countdown;
import dev.belikhun.luna.countdown.CountInstance.CountdownCallback;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.apache.commons.lang3.StringUtils;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.util.Collection;

public class CountdownCommand implements BasicCommand {
	protected class Instance {
		public int id;
		public String title;
		public CountInstance count;

		public Instance(String title) {
			this.title = title;
		}

		public void start(int seconds) {
			count = new CountInstance(seconds, new CountdownCallback() {

				@Override
				public void begin(BossBar bar) {
					Countdown.broadcast("<white>Sự kiện <green>" + Countdown.escape(title)
						+ "</green> sẽ bắt đầu sau " + CountInstance.readableTime(seconds)
						+ "<white> nữa!</white>");
				}
	
				@Override
				public void update(BossBar bar, double remain) {
					bar.setTitle(Countdown.legacy("<gray>#" + id + " <green>" + Countdown.escape(title)
						+ "</green> sau " + CountInstance.readableTime(remain) + "</gray>"));
				}
	
				@Override
				public void complete(BossBar bar) {
					String message = "<gray>#" + id + " <green>" + Countdown.escape(title) + "</green> đã bắt đầu!</gray>";
					bar.setTitle(Countdown.legacy(message));
					Countdown.broadcast("<white>Sự kiện " + message + "</white>");

					removeInstance();
				}
			});
		}

		public void stop() {
			count.stop("<white>Đã hủy bỏ <light_purple>" + Countdown.escape(title) + "</light_purple></white>");
			Countdown.broadcast("<white>Sự kiện <gray>(#" + id + ")</gray> <light_purple>"
				+ Countdown.escape(title) + "</light_purple> đã bị hủy!</white>");

			removeInstance();
		}

		public void removeInstance() {
			instances.remove(this);
		}
	}

	public static ArrayList<Instance> instances = new ArrayList<>();
	private static int nextId = 1;
	public CountdownCommand() { }

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission("countdown.countdown") && !(sender instanceof ConsoleCommandSender))
			return;

		if (args.length < 1) {
			sender.sendMessage(Countdown.mm(CommandStrings.usage("/countdown",
				CommandStrings.required("start|stop|stopall", "action"))));
			return;
		}

		switch (args[0]) {
			case "start":
				if (args.length < 2) {
					sender.sendMessage(Countdown.mm(CommandStrings.usage("/countdown",
						CommandStrings.literal("start"),
						CommandStrings.required("length", "time"),
						CommandStrings.optional("message", "text"))));
							return;
				}

				String title = "Sự Kiện Kết Thúc";
				int length = Countdown.parseTime(args[1]);

				if (length <= 0) {
					sender.sendMessage(Countdown.mm("<red>❌ Thời gian không hợp lệ: <white>" + Countdown.escape(args[1]) + "</white></red>"));
					return;
				}
		
				if (args.length >= 3)
					title = StringUtils.join(Arrays.copyOfRange(args, 2, args.length), ' ');
				
				countdown(title, length);
				break;
		
			case "stop":
				if (args.length < 2) {
					sender.sendMessage(Countdown.mm(CommandStrings.usage("/countdown",
						CommandStrings.literal("stop"),
						CommandStrings.required("id", "number"))));
					return;
				}

				int id;
				try {
					id = Integer.parseInt(args[1]);
				} catch (NumberFormatException exception) {
					sender.sendMessage(Countdown.mm("<red>❌ ID không hợp lệ: <white>" + Countdown.escape(args[1]) + "</white></red>"));
					return;
				}

				for (Instance instance : instances) {
					if (instance.id != id)
						continue;

					instance.stop();
					return;
				}

				sender.sendMessage(Countdown.mm("<red>❌ Không tìm thấy countdown với ID <white>" + id + "</white></red>"));
				return;

			case "stopall":
				stopAll();
				
				break;

			default:
				sender.sendMessage(Countdown.mm("<red>❌ Hành động <white>" + Countdown.escape(args[0]) + "</white> không tồn tại.</red>"));
				sender.sendMessage(Countdown.mm(CommandStrings.usage("/countdown",
					CommandStrings.required("start|stop|stopall", "action"))));
				return;
		}
	}

	public void countdown(String title, int seconds) {
		Instance instance = new Instance(title);
		instances.add(instance);
		instance.id = nextId++;
		instance.start(seconds);
	}

	public static void stopAll() {
		// Use iterator to avoid ConcurrentModificationException
		Iterator<Instance> iterator = instances.iterator();

		while (iterator.hasNext()) {
			Instance instance = iterator.next();
			iterator.remove();
			instance.stop();
		}
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission("countdown.countdown") && !(sender instanceof ConsoleCommandSender)) {
			return List.of();
		}

		if (args.length == 1) {
			return CommandCompletions.filterPrefix(List.of("start", "stop", "stopall"), args[0]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
			return CommandCompletions.filterPrefix(List.of("30", "60", "120", "300", "30s", "1m", "5m", "10m"), args[1]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
			ArrayList<String> ids = new ArrayList<>();
			for (Instance instance : instances) {
				ids.add(String.valueOf(instance.id));
			}
			return CommandCompletions.filterPrefix(ids, args[1]);
		}

		if (args.length >= 3 && args[0].equalsIgnoreCase("start")) {
			return CommandCompletions.filterPrefix(List.of("Sự_kiện", "Boss", "PvP", "Khai_thác"), args[args.length - 1]);
		}

		return List.of();
	}

	@Override
	public String permission() {
		return "countdown.countdown";
	}
}

