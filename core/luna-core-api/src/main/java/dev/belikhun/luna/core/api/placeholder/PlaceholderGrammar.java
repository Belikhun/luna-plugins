package dev.belikhun.luna.core.api.placeholder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one parser and printer for placeholder text.
 *
 * Reading: any {@link PlaceholderSyntax} becomes a {@link PlaceholderRef}. The
 * namespace is the first word, the {@code _safe} suffix is recognised on any
 * namespace, and MiniMessage tag arguments are split on the colon with single
 * or double quotes stripped.
 *
 * Writing: a ref becomes text in the syntax asked for. Explicit arguments
 * become tokens in the percent form and stay arguments in the tag form, so a
 * value moved between a TAB config and a MiniMessage template keeps its
 * meaning. A tag name only allows {@code [a-zA-Z0-9_-]}, which is why an
 * argument such as a glyph must ride after the colon there.
 */
public final class PlaceholderGrammar {
	/** The suffix that asks for the value's percent signs to be escaped. */
	public static final String SAFE_SUFFIX = "safe";
	private static final Pattern TAG_NAME = Pattern.compile("[A-Za-z0-9_-]+");
	private static final Pattern PERCENT_TOKEN = Pattern.compile("%([A-Za-z0-9-]+(?:_[^%]*)?)%");
	private static final Pattern BRACE_TOKEN = Pattern.compile("\\{([A-Za-z0-9-]+(?:_[^{}]*)?)}");
	private static final Pattern TAG_TOKEN = Pattern.compile("<([A-Za-z0-9_-]+)((?::(?:'[^']*'|\"[^\"]*\"|[^:>'\"]*))*)>");
	/**
	 * Namespaces that are the first word of a standard MiniMessage tag, so a scan
	 * without a registry does not read {@code <dark_gray>} as a placeholder.
	 */
	private static final Set<String> MINI_MESSAGE_NAMESPACES = Set.of("dark", "light", "lang", "click", "hover", "shadow");
	/** Keeps a tag ref when it has a key at all and is not a standard MiniMessage tag. */
	public static final Predicate<PlaceholderRef> DEFAULT_TAG_FILTER = ref ->
		!ref.tokens().isEmpty() && !MINI_MESSAGE_NAMESPACES.contains(ref.namespace());

	private PlaceholderGrammar() {
	}

	/**
	 * Parses one placeholder written in any syntax.
	 *
	 * @param text the placeholder, delimiters optional; null and blank give an empty ref
	 * @return the ref
	 */
	public static PlaceholderRef parse(String text) {
		if (text == null) {
			return empty("");
		}

		String trimmed = text.trim();

		if (trimmed.isEmpty()) {
			return empty(trimmed);
		}

		if (trimmed.length() >= 3 && trimmed.startsWith("%") && trimmed.endsWith("%")) {
			return fromIdentifier(trimmed.substring(1, trimmed.length() - 1), List.of(), PlaceholderSyntax.PERCENT, trimmed);
		}

		if (trimmed.length() >= 3 && trimmed.startsWith("{") && trimmed.endsWith("}")) {
			return fromIdentifier(trimmed.substring(1, trimmed.length() - 1), List.of(), PlaceholderSyntax.BRACE, trimmed);
		}

		if (trimmed.length() >= 3 && trimmed.startsWith("<") && trimmed.endsWith(">")) {
			return parseTag(trimmed);
		}

		return fromIdentifier(trimmed, List.of(), PlaceholderSyntax.BARE, trimmed);
	}

	/**
	 * Parses a MiniMessage tag such as {@code <luna_player_status:'⏺'>}.
	 *
	 * @param tag the tag including its angle brackets
	 * @return the ref; an empty ref when the text is not a tag
	 */
	public static PlaceholderRef parseTag(String tag) {
		if (tag == null) {
			return empty("");
		}

		String trimmed = tag.trim();

		if (trimmed.length() < 3 || !trimmed.startsWith("<") || !trimmed.endsWith(">")) {
			return empty(trimmed);
		}

		String inner = trimmed.substring(1, trimmed.length() - 1);
		List<String> parts = splitTagArguments(inner);

		if (parts.isEmpty() || !TAG_NAME.matcher(parts.get(0)).matches()) {
			return empty(trimmed);
		}

		return fromIdentifier(parts.get(0), parts.subList(1, parts.size()), PlaceholderSyntax.MINI_TAG, trimmed);
	}

	/**
	 * Writes a ref in the syntax asked for.
	 *
	 * @param ref    the ref
	 * @param syntax the target syntax
	 * @return the text, with the safe suffix kept when the ref carries it
	 */
	public static String format(PlaceholderRef ref, PlaceholderSyntax syntax) {
		if (syntax == PlaceholderSyntax.MINI_TAG) {
			StringBuilder builder = new StringBuilder("<").append(tagName(ref));

			for (String argument : ref.args()) {
				builder.append(':').append(quoteArgument(argument));
			}

			if (ref.safe()) {
				builder.append(":").append(SAFE_SUFFIX);
			}

			return builder.append('>').toString();
		}

		StringBuilder identifier = new StringBuilder(ref.identifier());

		for (String argument : ref.args()) {
			identifier.append('_').append(argument);
		}

		if (ref.safe()) {
			identifier.append('_').append(SAFE_SUFFIX);
		}

		return switch (syntax) {
			case PERCENT -> "%" + identifier + "%";
			case BRACE -> "{" + identifier + "}";
			default -> identifier.toString();
		};
	}

	/**
	 * The tag name a ref would have in MiniMessage: the identifier when every
	 * character is allowed there, otherwise the longest allowed prefix, with the
	 * rest becoming arguments in {@link #format}.
	 */
	public static String tagName(PlaceholderRef ref) {
		String identifier = ref.identifier();

		if (TAG_NAME.matcher(identifier).matches()) {
			return identifier;
		}

		StringBuilder allowed = new StringBuilder(ref.namespace());

		for (String token : ref.tokens()) {
			if (!TAG_NAME.matcher(token).matches()) {
				break;
			}

			allowed.append('_').append(token);
		}

		return allowed.toString();
	}

	/**
	 * Rewrites a ref so that everything a tag name cannot hold becomes explicit
	 * arguments, which is the form MiniMessage needs.
	 */
	public static PlaceholderRef tagSafe(PlaceholderRef ref) {
		List<String> keptTokens = new ArrayList<>();
		List<String> movedArgs = new ArrayList<>();
		boolean moving = false;

		for (String token : ref.tokens()) {
			if (!moving && TAG_NAME.matcher(token).matches()) {
				keptTokens.add(token);
				continue;
			}

			moving = true;
			movedArgs.add(token);
		}

		if (movedArgs.isEmpty()) {
			return ref;
		}

		movedArgs.addAll(ref.args());
		return new PlaceholderRef(ref.namespace(), keptTokens, movedArgs, ref.safe(), ref.syntax(), ref.source());
	}

	/**
	 * Finds every placeholder written in the given syntaxes inside free text.
	 *
	 * Percent and brace tokens must start with a namespace word made of letters,
	 * digits and dashes, so "50% off, 60% on" is not read as a placeholder.
	 *
	 * @param text     the text to scan
	 * @param syntaxes which forms count; BARE is never scanned for
	 * @return the segments in order, literal text between the refs kept as-is
	 */
	public static List<Segment> scan(String text, Set<PlaceholderSyntax> syntaxes) {
		return scan(text, syntaxes, DEFAULT_TAG_FILTER);
	}

	/**
	 * Finds every placeholder inside free text, deciding tag refs with a filter.
	 *
	 * A MiniMessage tag is only a placeholder when something claims its
	 * namespace; a renderer with a registry passes that test here, so
	 * {@code <gold>} and {@code <dark_gray>} stay literal text.
	 *
	 * @param text      the text to scan
	 * @param syntaxes  which forms count; BARE is never scanned for
	 * @param tagFilter which MINI_TAG refs count as placeholders
	 * @return the segments in order
	 */
	public static List<Segment> scan(String text, Set<PlaceholderSyntax> syntaxes, Predicate<PlaceholderRef> tagFilter) {
		List<Segment> segments = new ArrayList<>();

		if (text == null || text.isEmpty()) {
			return segments;
		}

		List<Range> ranges = new ArrayList<>();

		if (syntaxes.contains(PlaceholderSyntax.PERCENT)) {
			collect(ranges, PERCENT_TOKEN.matcher(text), PlaceholderSyntax.PERCENT);
		}

		if (syntaxes.contains(PlaceholderSyntax.BRACE)) {
			collect(ranges, BRACE_TOKEN.matcher(text), PlaceholderSyntax.BRACE);
		}

		if (syntaxes.contains(PlaceholderSyntax.MINI_TAG)) {
			collect(ranges, TAG_TOKEN.matcher(text), PlaceholderSyntax.MINI_TAG);
		}

		ranges.sort((left, right) -> Integer.compare(left.start, right.start));
		int cursor = 0;

		for (Range range : ranges) {
			// overlapping matches from two syntaxes: the earlier one wins
			if (range.start < cursor) {
				continue;
			}

			String source = text.substring(range.start, range.end);
			PlaceholderRef ref = parse(source);

			if (range.syntax == PlaceholderSyntax.MINI_TAG && tagFilter != null && !tagFilter.test(ref)) {
				continue;
			}

			if (range.start > cursor) {
				segments.add(Segment.literal(text.substring(cursor, range.start)));
			}

			segments.add(Segment.ref(ref, source));
			cursor = range.end;
		}

		if (cursor < text.length()) {
			segments.add(Segment.literal(text.substring(cursor)));
		}

		return segments;
	}

	/** A run of literal text or one placeholder inside scanned text. */
	public record Segment(String literal, PlaceholderRef ref, String source) {
		public boolean isRef() {
			return ref != null;
		}

		static Segment literal(String text) {
			return new Segment(text, null, text);
		}

		static Segment ref(PlaceholderRef ref, String source) {
			return new Segment(null, ref, source);
		}
	}

	private record Range(int start, int end, PlaceholderSyntax syntax) {
	}

	private static void collect(List<Range> ranges, Matcher matcher, PlaceholderSyntax syntax) {
		while (matcher.find()) {
			ranges.add(new Range(matcher.start(), matcher.end(), syntax));
		}
	}

	private static PlaceholderRef fromIdentifier(String identifier, List<String> args, PlaceholderSyntax syntax, String source) {
		String trimmed = identifier == null ? "" : identifier.trim();

		if (trimmed.isEmpty()) {
			return empty(source);
		}

		List<String> words = new ArrayList<>(List.of(trimmed.split("_", -1)));
		String namespace = words.remove(0);
		List<String> explicitArgs = new ArrayList<>(args);
		boolean safe = false;

		// the safe suffix rides as the last argument in tag form, as the last
		// token in every other form; a bare "%luna_safe%" is a key named safe
		if (!explicitArgs.isEmpty() && SAFE_SUFFIX.equalsIgnoreCase(explicitArgs.get(explicitArgs.size() - 1))) {
			explicitArgs.remove(explicitArgs.size() - 1);
			safe = true;
		} else if (words.size() > 1 && SAFE_SUFFIX.equalsIgnoreCase(words.get(words.size() - 1))) {
			words.remove(words.size() - 1);
			safe = true;
		}

		return new PlaceholderRef(namespace, words, explicitArgs, safe, syntax, source);
	}

	private static PlaceholderRef empty(String source) {
		return new PlaceholderRef("", List.of(), List.of(), false, PlaceholderSyntax.BARE, source);
	}

	private static List<String> splitTagArguments(String inner) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char quote = 0;

		for (int index = 0; index < inner.length(); index++) {
			char character = inner.charAt(index);

			if (quote != 0) {
				if (character == quote) {
					quote = 0;
				} else {
					current.append(character);
				}

				continue;
			}

			if (character == '\'' || character == '"') {
				quote = character;
				continue;
			}

			if (character == ':') {
				parts.add(current.toString());
				current.setLength(0);
				continue;
			}

			current.append(character);
		}

		parts.add(current.toString());
		return parts;
	}

	private static String quoteArgument(String argument) {
		if (argument.isEmpty() || argument.indexOf(':') >= 0 || argument.indexOf('>') >= 0 || argument.indexOf('\'') >= 0 || argument.indexOf(' ') >= 0) {
			return "'" + argument.replace("'", "\\'") + "'";
		}

		if (TAG_NAME.matcher(argument).matches()) {
			return argument;
		}

		return "'" + argument + "'";
	}
}
