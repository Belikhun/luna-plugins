package dev.belikhun.luna.core.api.placeholder;

import java.util.List;
import java.util.Locale;

/**
 * One placeholder as written, split into its parts but not yet matched to a
 * spec.
 *
 * The namespace is the first underscore-separated word, lowercased. The tokens
 * are every word after it, kept in their original case because an argument may
 * be a glyph or a world name. Explicit arguments come from the MiniMessage form
 * ({@code <ns_key:arg>}); the percent form has none, its arguments are tokens.
 *
 * @param namespace the namespace, lowercase
 * @param tokens    the words after the namespace, original case, never containing "_"
 * @param args      explicit arguments, in order; empty for every syntax but MINI_TAG
 * @param safe      true when the {@code _safe} suffix asked for percent escaping
 * @param syntax    how the placeholder was written
 * @param source    the exact text that was parsed, delimiters included
 */
public record PlaceholderRef(
	String namespace,
	List<String> tokens,
	List<String> args,
	boolean safe,
	PlaceholderSyntax syntax,
	String source
) {
	public PlaceholderRef {
		namespace = namespace == null ? "" : namespace.toLowerCase(Locale.ROOT);
		tokens = tokens == null ? List.of() : List.copyOf(tokens);
		args = args == null ? List.of() : List.copyOf(args);
		source = source == null ? "" : source;
	}

	/**
	 * The identifier without delimiters, arguments or the safe suffix:
	 * {@code luna_tps_bar_10}.
	 */
	public String identifier() {
		StringBuilder builder = new StringBuilder(namespace);

		for (String token : tokens) {
			builder.append('_').append(token);
		}

		return builder.toString();
	}

	/**
	 * The identifier lowercased, which is what two spellings of one placeholder
	 * share.
	 */
	public String normalizedIdentifier() {
		return identifier().toLowerCase(Locale.ROOT);
	}

	/** The tokens joined with underscores, original case; empty when there are none. */
	public String key() {
		return String.join("_", tokens);
	}

	/** A copy of this ref with a different safe flag. */
	public PlaceholderRef withSafe(boolean value) {
		return new PlaceholderRef(namespace, tokens, args, value, syntax, source);
	}

	/** A copy of this ref carrying explicit arguments. */
	public PlaceholderRef withArgs(List<String> value) {
		return new PlaceholderRef(namespace, tokens, value, safe, syntax, source);
	}

	/**
	 * Builds a ref from a bare identifier, for callers that already hold one.
	 *
	 * @param identifier {@code ns_key_more}, delimiters optional
	 * @return the ref, with an empty namespace when the identifier is blank
	 */
	public static PlaceholderRef of(String identifier) {
		return PlaceholderGrammar.parse(identifier);
	}
}
