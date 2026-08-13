package dev.belikhun.luna.legacy.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * luna's MiniMessage strings rendered for a 1.12.2 client.
 *
 * Every message luna sends is authored as MiniMessage, and on the modern lines it
 * reaches the client as a component tree with its colours intact. 1.12.2 has no such
 * thing: the protocol carries chat as a component too, but its colour is one of
 * sixteen named codes, and there is no hex.
 *
 * So a message is parsed here and serialised straight back down to a `§`-coded
 * string. Adventure's section serializer does the downsampling itself - a hex colour
 * becomes the nearest of the sixteen - which is why this is a handful of lines rather
 * than a colour-distance table.
 *
 * **A gradient becomes one solid colour before any of that happens.** Letting it
 * downsample per character is technically correct and practically wrong: a gradient
 * colours every character, so nine characters become nine colour runs and eighteen
 * extra bytes of `§` codes. Chat survives that; a container title does not, and the
 * one on `/transactions` came out mangled. Collapsing to the first stop keeps the
 * intent - luna's gradients all run between two nearby shades - at a fraction of
 * the length.
 *
 * **What else is lost is worth stating plainly**, because it is a real behavioural
 * difference rather than a bug to fix later:
 *
 * - the second stop of a gradient is not rendered at all;
 * - click and hover events do not survive, since they are not expressible in a
 *   legacy string. Anything that needs them has to send a component built by hand.
 */
public final class LegacyText {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

	/**
	 * `legacySection` is deliberately not `legacyAmpersand`: the section sign is what
	 * the game itself reads, and building it without hex support is what makes the
	 * serializer downsample rather than emit a code a 1.12.2 client cannot parse.
	 */
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

	/** `<gradient>` / `<gradient:a:b>` / `<gradient:a:b:0.5>`, opening tag only. */
	private static final Pattern GRADIENT_OPEN = Pattern.compile("<gradient((?::[^>]*)?)>", Pattern.CASE_INSENSITIVE);

	private static final Pattern GRADIENT_CLOSE = Pattern.compile("</gradient>", Pattern.CASE_INSENSITIVE);

	/**
	 * The fallback when a gradient names no stop.
	 *
	 * Spelled as a `color` tag rather than as `<white>` because the closing
	 * `</gradient>` is rewritten to `</color>`, and a `</color>` that closes nothing
	 * is left in the output as literal text.
	 */
	private static final String WHITE_TAG = "<color:white>";

	private LegacyText() {
	}

	/**
	 * Rewrite every gradient as its first stop.
	 *
	 * Done on the string rather than on the parsed tree because MiniMessage has
	 * already expanded a gradient into one coloured child per character by the time
	 * there is a tree to walk; the tag still says what the author meant.
	 */
	private static String collapseGradients(String miniMessage) {
		if (miniMessage.indexOf("<gradient") < 0 && miniMessage.indexOf("<GRADIENT") < 0) {
			return miniMessage;
		}

		Matcher matcher = GRADIENT_OPEN.matcher(miniMessage);
		StringBuffer out = new StringBuffer();

		while (matcher.find()) {
			matcher.appendReplacement(out, Matcher.quoteReplacement(solidTag(matcher.group(1))));
		}

		matcher.appendTail(out);

		return GRADIENT_CLOSE.matcher(out.toString()).replaceAll("</color>");
	}

	/** The first colour stop in a gradient's arguments, as a solid colour tag. */
	private static String solidTag(String arguments) {
		if (arguments == null || arguments.isEmpty()) {
			return WHITE_TAG;
		}

		for (String part : arguments.substring(1).split(":")) {
			String stop = part.trim();

			// a trailing number is the phase, not a colour
			if (stop.isEmpty() || isNumeric(stop)) {
				continue;
			}

			return "<color:" + stop + ">";
		}

		return WHITE_TAG;
	}

	private static boolean isNumeric(String value) {
		try {
			Double.parseDouble(value);

			return true;
		} catch (NumberFormatException notANumber) {
			return false;
		}
	}

	/** A MiniMessage string as `§`-coded legacy text. */
	public static String legacy(String miniMessage) {
		if (miniMessage == null || miniMessage.isEmpty()) {
			return "";
		}

		return LEGACY.serialize(MINI_MESSAGE.deserialize(collapseGradients(miniMessage)));
	}

	/** An already-built component as `§`-coded legacy text. */
	public static String legacy(Component component) {
		return component == null ? "" : LEGACY.serialize(component);
	}

	/** Parse MiniMessage without rendering it, for a caller that wants the tree. */
	public static Component parse(String miniMessage) {
		return miniMessage == null ? Component.empty() : MINI_MESSAGE.deserialize(collapseGradients(miniMessage));
	}
}
