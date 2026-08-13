package dev.belikhun.luna.core.api.ui;

/**
 * The title line above a luna screen, as MiniMessage.
 *
 * Every chest GUI in the fleet is titled the same way: the module's name, then
 * where in it the player is, separated by `»` and stepping down through the
 * title shades. A screen that titles itself some other way reads as a different
 * product, which is the whole reason this is one function rather than a habit.
 *
 * It is here rather than in {@link LunaUi} because that class is Bukkit-typed:
 * a fabric, forge or neoforge screen cannot call it, and the platforms that
 * could not reach the rule are exactly the ones that drifted from it. This
 * returns the string; each platform renders it through its own MiniMessage
 * bridge, and `LunaUi` keeps the convenience overload that returns a Component.
 *
 * The shades are deliberately dark. A chest title is drawn on the light
 * inventory background, so it follows the palette's own guidance for titles
 * rather than the bright shades chat uses.
 */
public final class LunaGuiTitle {
	private static final String SEPARATOR = "<color:" + LunaPalette.GUI_TITLE_TERTIARY + "> » </color>";

	private LunaGuiTitle() {
	}

	/** One untitled segment: a screen with no breadcrumb above it. */
	public static String plain(String text) {
		return "<color:" + LunaPalette.GUI_TITLE_PRIMARY + ">" + (text == null ? "" : text) + "</color>";
	}

	/**
	 * A breadcrumb: `Module » Section » Screen`.
	 *
	 * Blank segments are dropped rather than rendered as an empty step, so a
	 * caller may pass an optional segment without branching around it.
	 */
	public static String breadcrumb(String... segments) {
		if (segments == null || segments.length == 0) {
			return plain("");
		}

		StringBuilder builder = new StringBuilder();
		int depth = 0;

		for (String segment : segments) {
			String text = segment == null ? "" : segment.trim();

			if (text.isBlank()) {
				continue;
			}

			if (!builder.isEmpty()) {
				builder.append(SEPARATOR);
			}

			builder.append("<color:").append(shadeFor(depth)).append(">").append(text).append("</color>");
			depth++;
		}

		return builder.isEmpty() ? plain("") : builder.toString();
	}

	private static String shadeFor(int depth) {
		return switch (depth) {
			case 0 -> LunaPalette.GUI_TITLE_PRIMARY;
			case 1 -> LunaPalette.GUI_TITLE_SECONDARY;
			default -> LunaPalette.GUI_TITLE_TERTIARY;
		};
	}
}
