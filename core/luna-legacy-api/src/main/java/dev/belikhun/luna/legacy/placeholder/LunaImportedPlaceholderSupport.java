package dev.belikhun.luna.legacy.placeholder;

import dev.belikhun.luna.legacy.string.Strings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.time.Duration;
import java.util.regex.Pattern;

public final class LunaImportedPlaceholderSupport {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
	private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
		.character('&')
		.hexColors()
		.build();
	private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("(?i)(?:§|&)[0-9A-FK-ORX]");
	private static final char[] SMALL_NUMBER_CHARS = {'₀', '₁', '₂', '₃', '₄', '₅', '₆', '₇', '₈', '₉'};

	private LunaImportedPlaceholderSupport() {
	}

	public static String weatherText(boolean raining, boolean thundering) {
		if (raining) {
			return thundering ? "Mưa bão" : "Mưa";
		}
		if (thundering) {
			return "Sấm sét";
		}
		return "Trong lành";
	}

	public static String weatherIcon(boolean raining, boolean thundering) {
		if (raining) {
			return thundering ? "⛈" : "🌧";
		}
		if (thundering) {
			return "🌩";
		}
		return "🌤";
	}

	public static String weatherColor(boolean raining, boolean thundering) {
		if (raining) {
			return thundering ? "<c:#ff9d73>" : "<c:#75cdff>";
		}
		if (thundering) {
			return "<c:#ffea5e>";
		}
		return "<c:#a8ffbd>";
	}

	public static String formatDurationSeconds(long seconds) {
		Duration duration = Duration.ofSeconds(Math.max(0L, seconds));
		long hours = duration.toHours();
		long minutes = duration.toMinutes() % 60L;
		long secs = duration.getSeconds() % 60L;

		StringBuilder builder = new StringBuilder();
		if (hours > 0L) {
			builder.append(hours).append("h ");
		}
		if (minutes > 0L) {
			builder.append(minutes).append("m ");
		}
		if (secs > 0L || builder.length() == 0) {
			builder.append(secs).append("s");
		}
		return builder.toString().trim();
	}

	public static String voiceChatStatus(VoiceChatStatus status) {
		switch (status == null ? VoiceChatStatus.UNKNOWN : status) {
			case CONNECTED:
				return "<c:#96ff70>🔊<reset>";

			case NOT_INSTALLED:
				return "<c:#ff7a7a>🚫<reset>";

			case DISCONNECTED:
				return "<c:#f6ff75><st>🔌<reset>";

			case MUTED:
				return "<gray>🔇<reset>";

			case UNKNOWN:
			default:
				// javac cannot see that the enum is covered once the switch stops
				// being an expression, so the last arm doubles as the default
				return "<c:#ff7a7a>⚠<reset>";
		}
	}

	public static String playerLevel(int level) {
		int safeLevel = Math.max(0, level);
		String color = "#66c8ff";
		if (safeLevel >= 90) {
			color = "#2b27de";
		} else if (safeLevel >= 80) {
			color = "#716ee3";
		} else if (safeLevel >= 70) {
			color = "#d76fab";
		} else if (safeLevel >= 60) {
			color = "#ff727d";
		} else if (safeLevel >= 50) {
			color = "#ff8468";
		} else if (safeLevel >= 40) {
			color = "#fbc06c";
		} else if (safeLevel >= 30) {
			color = "#f2f36e";
		} else if (safeLevel >= 20) {
			color = "#89ff77";
		} else if (safeLevel >= 10) {
			color = "#67efe6";
		}
		return "<gray>ₗᵥ<c:" + color + ">" + toTinyInt(safeLevel) + "<reset>";
	}

	public static String playerStatusDot(WorldKind worldKind, String dot) {
		String safeDot = (dot == null || dot.isEmpty()) ? "█" : dot;
		return "<c:" + dimensionColor(worldKind) + ">" + safeDot + "<reset>";
	}

	/**
	 * A glyph coloured by the dimension the player is standing in.
	 *
	 * Separate from {@link #playerStatusDot} on purpose: the two answer different
	 * questions and only agree by accident on a backend. Status is about the server
	 * a player is on, which the proxy colours by that server's accent; this is about
	 * the dimension, which only the backend can know.
	 *
	 * @param worldKind the dimension; null and unrecognised worlds read as CUSTOM
	 * @param glyph the glyph to colour, or null/empty for the default
	 * @return MiniMessage source for the coloured glyph
	 */
	public static String playerDimensionDot(WorldKind worldKind, String glyph) {
		String safeGlyph = (glyph == null || glyph.isEmpty()) ? "◎" : glyph;
		return "<c:" + dimensionColor(worldKind) + ">" + safeGlyph + "<reset>";
	}

	/** Overworld green, nether red, end magenta, anything else the custom yellow. */
	private static String dimensionColor(WorldKind worldKind) {
		String color;

		switch (worldKind == null ? WorldKind.CUSTOM : worldKind) {
			case NORMAL:
				color = "#55ff55";
				break;
			case NETHER:
				color = "#ff5555";
				break;
			case END:
				color = "#ff55ff";
				break;
			default:
				color = "#f2f36e";
				break;
		}
		return color;
	}

	public static String stripLegacyColors(String value) {
		if (value == null || Strings.isBlank(value)) {
			return "";
		}
		return LEGACY_COLOR_PATTERN.matcher(value).replaceAll("");
	}

	public static String stripMiniMessage(String value) {
		if (value == null || Strings.isBlank(value)) {
			return "";
		}
		return MINI_MESSAGE.stripTags(value);
	}

	public static String miniMessageToLegacy(String value) {
		if (value == null) {
			return null;
		}
		try {
			Component component = MINI_MESSAGE.deserialize(value);
			return LEGACY_SERIALIZER.serialize(component);
		} catch (Throwable ignored) {
			return value;
		}
	}

	public static String toTinyInt(int number) {
		int safeNumber = Math.max(0, number);
		String output = "";
		while (safeNumber > 0) {
			output = SMALL_NUMBER_CHARS[safeNumber % 10] + output;
			safeNumber = Math.floorDiv(safeNumber, 10);
		}
		if (output.length() <= 1) {
			output = SMALL_NUMBER_CHARS[0] + output;
		}
		return output;
	}

	public enum VoiceChatStatus {
		DISCONNECTED,
		UNKNOWN,
		NOT_INSTALLED,
		MUTED,
		CONNECTED
	}

	public enum WorldKind {
		NORMAL,
		NETHER,
		END,
		CUSTOM
	}
}
