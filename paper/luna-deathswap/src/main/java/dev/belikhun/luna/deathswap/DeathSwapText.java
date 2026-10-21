package dev.belikhun.luna.deathswap;

import dev.belikhun.luna.core.api.string.MiniText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text in the look the other luna minigames (manhunt, iceboat) use: no chat
 * prefix, one coloured icon leading each line, values in white, and
 * announcement blocks of " ● " lines under a bold header.
 */
public final class DeathSwapText {
	public static final String SUCCESS = "<green>✔</green> ";
	public static final String ERROR = "<red>❌</red> ";
	public static final String INFO = "<yellow>ℹ</yellow> ";
	public static final String WAIT = "<yellow>⌛</yellow> ";
	public static final String WARN = "<yellow>⚠</yellow> ";
	public static final String DOT = " <dark_gray>●</dark_gray> ";

	public static final String COMMAND = "#59c3ff";
	public static final String ACCENT_DANGER = "#ff6b6b";
	public static final String ACCENT_GOLD = "#ffd166";
	public static final String ACCENT_GREEN = "#7fd36b";
	public static final String ACCENT_BLUE = "#36a8ff";
	public static final String ACCENT_TIME = "#32faa7";
	public static final String ACCENT_FIRE = "#ffc012";

	/** The game's name, as the sidebar and titles draw it. */
	public static final String BRAND = "<b><gradient:#FF6B6B:#FFB86C>DEATH</gradient><gradient:#C084FC:#60A5FA>SWAP</gradient></b>";

	private static final MiniMessage MINI = MiniMessage.miniMessage();
	private static final Pattern LEGACY_HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
	private static final Pattern LEGACY_SPIGOT_HEX = Pattern.compile("[&§]x((?:[&§][0-9a-fA-F]){6})");
	private static final Pattern LEGACY_CODE = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

	private DeathSwapText() {
	}

	public static Component mm(String miniMessage) {
		return MINI.deserialize(miniMessage == null ? "" : miniMessage);
	}

	public static String escape(String value) {
		return MiniText.escape(value == null ? "" : value);
	}

	public static void send(CommandSender to, String miniMessage) {
		if (to != null) {
			to.sendMessage(mm(miniMessage));
		}
	}

	public static void broadcast(String miniMessage) {
		Component component = mm(miniMessage);
		for (Player player : Bukkit.getOnlinePlayers()) {
			player.sendMessage(component);
		}
		Bukkit.getConsoleSender().sendMessage(component);
	}

	/** Several lines sent as one block, with a blank line above and below. */
	public static void broadcastBlock(String... lines) {
		broadcast(" ");
		for (String line : lines) {
			broadcast(line);
		}
		broadcast(" ");
	}

	public static String blockHeader(String text) {
		return " " + INFO + "<white><b>" + text + "</b></white>";
	}

	public static String blockLine(String text) {
		// left open on purpose: a player display inside (the name format starts
		// with the rank prefix and a <reset>) closes this <white> itself, and a
		// closing tag with nothing left to close is printed as text ("</white>")
		return " <yellow>●</yellow> <white>" + text;
	}

	public static String white(String value) {
		return "<white>" + escape(value) + "</white>";
	}

	public static String color(String hex, String inner) {
		return "<" + hex + ">" + inner + "</" + hex + ">";
	}

	/** A command shown the manhunt way: blue, click to paste, hover to explain. */
	public static String command(String command) {
		String safe = escape(command);
		return "<click:suggest_command:'" + command.replace("'", "") + "'><hover:show_text:'<gray>Bấm để dán lệnh</gray>'>"
			+ color(COMMAND, safe) + "</hover></click>";
	}

	public static void title(Player player, String title, String subtitle, int stayMillis) {
		player.showTitle(Title.title(
			mm(title),
			mm(subtitle),
			Title.Times.times(Duration.ZERO, Duration.ofMillis(stayMillis), Duration.ofMillis(250))
		));
	}

	/** m:ss, or h:mm:ss once past an hour. */
	public static String clock(long seconds) {
		long safe = Math.max(0, seconds);
		long hours = safe / 3600;
		long minutes = (safe % 3600) / 60;
		long secs = safe % 60;
		return hours > 0
			? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
			: String.format(Locale.ROOT, "%d:%02d", minutes, secs);
	}

	/** Replace every %key% the map knows; the rest is left for PlaceholderAPI. */
	public static String fill(String template, Map<String, String> values) {
		if (template == null || template.isEmpty()) {
			return "";
		}

		String out = template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			out = out.replace("%" + entry.getKey() + "%", entry.getValue() == null ? "" : entry.getValue());
		}
		return out;
	}

	/** Run PlaceholderAPI when it is there, and turn any legacy colour codes it returned into MiniMessage. */
	public static String papi(Player player, String text) {
		if (text == null || text.indexOf('%') < 0 || player == null) {
			return text == null ? "" : text;
		}

		String resolved = text;
		if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			resolved = PlaceholderHook.apply(player, text);
		}
		return legacyToMini(resolved);
	}

	public static String legacyToMini(String text) {
		if (text == null || (text.indexOf('&') < 0 && text.indexOf('§') < 0)) {
			return text == null ? "" : text;
		}

		Matcher spigot = LEGACY_SPIGOT_HEX.matcher(text);
		StringBuilder out = new StringBuilder();
		while (spigot.find()) {
			String hex = spigot.group(1).replaceAll("[&§]", "");
			spigot.appendReplacement(out, Matcher.quoteReplacement("<#" + hex + ">"));
		}
		spigot.appendTail(out);

		String value = LEGACY_HEX.matcher(out.toString()).replaceAll("<#$1>");
		Matcher code = LEGACY_CODE.matcher(value);
		StringBuilder result = new StringBuilder();
		while (code.find()) {
			code.appendReplacement(result, Matcher.quoteReplacement(legacyTag(Character.toLowerCase(code.group(1).charAt(0)))));
		}
		code.appendTail(result);
		return result.toString();
	}

	private static String legacyTag(char code) {
		return switch (code) {
			case '0' -> "<black>";
			case '1' -> "<dark_blue>";
			case '2' -> "<dark_green>";
			case '3' -> "<dark_aqua>";
			case '4' -> "<dark_red>";
			case '5' -> "<dark_purple>";
			case '6' -> "<gold>";
			case '7' -> "<gray>";
			case '8' -> "<dark_gray>";
			case '9' -> "<blue>";
			case 'a' -> "<green>";
			case 'b' -> "<aqua>";
			case 'c' -> "<red>";
			case 'd' -> "<light_purple>";
			case 'e' -> "<yellow>";
			case 'f' -> "<white>";
			case 'k' -> "<obfuscated>";
			case 'l' -> "<b>";
			case 'm' -> "<st>";
			case 'n' -> "<u>";
			case 'o' -> "<i>";
			default -> "<reset>";
		};
	}

	/** Kept in its own class so nothing loads PlaceholderAPI's classes when it is absent. */
	private static final class PlaceholderHook {
		private static String apply(Player player, String text) {
			try {
				return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
			} catch (Throwable throwable) {
				return text;
			}
		}
	}
}
