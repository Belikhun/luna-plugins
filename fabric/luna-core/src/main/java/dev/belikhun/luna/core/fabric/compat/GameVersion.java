package dev.belikhun.luna.core.fabric.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Which Minecraft this jar ended up running on.
 *
 * One build of this mod serves a whole game line, so anything that changed inside
 * that range is gated on a version test rather than assumed. The reading comes
 * from the loader's own metadata, not from a game class, because that is the one
 * source that cannot be renamed by a remap.
 *
 * Two numbering schemes are in circulation: the historical {@code 1.<minor>.<patch>}
 * and the date-based {@code YY.<n>} that started at 26. A leading number of 22 or
 * more is therefore the newer scheme and sorts above every {@code 1.x}.
 */
public final class GameVersion {
	/** Anything at or above this leading number is a date-based version. */
	private static final int DATE_SCHEME_FLOOR = 22;

	private static final String DISPLAY = readDisplay();
	private static final int[] PARTS = parse(DISPLAY);

	private GameVersion() {
	}

	/** The version as the loader reports it, e.g. "1.21.1" or "26.2". */
	public static String display() {
		return DISPLAY;
	}

	/** Whether the running game is at least the given version. */
	public static boolean atLeast(int... target) {
		for (int index = 0; index < target.length; index++) {
			int running = index < PARTS.length ? PARTS[index] : 0;
			if (running != target[index]) {
				return running > target[index];
			}
		}

		return true;
	}

	private static String readDisplay() {
		return FabricLoader.getInstance()
			.getModContainer("minecraft")
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("");
	}

	/**
	 * Leading dotted numbers, normalised so both schemes compare as one series.
	 * A snapshot ("26w14a") has no usable numbers and is treated as newer than
	 * anything released, which is the safe reading for a compatibility gate.
	 */
	private static int[] parse(String version) {
		String[] chunks = version.split("\\.");
		int[] parts = new int[chunks.length];
		int count = 0;

		for (String chunk : chunks) {
			int digits = 0;
			while (digits < chunk.length() && Character.isDigit(chunk.charAt(digits))) {
				digits++;
			}

			if (digits == 0) {
				break;
			}

			parts[count++] = Integer.parseInt(chunk.substring(0, digits));
		}

		if (count == 0) {
			return new int[] {Integer.MAX_VALUE};
		}

		int[] trimmed = new int[count];
		System.arraycopy(parts, 0, trimmed, 0, count);

		// a date-based version outranks every 1.x, so it is compared as if the
		// leading "1." the older scheme carries were an even bigger number
		if (trimmed[0] >= DATE_SCHEME_FLOOR) {
			return new int[] {Integer.MAX_VALUE};
		}

		return trimmed;
	}
}
