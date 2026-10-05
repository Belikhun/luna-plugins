package dev.belikhun.luna.core.api.placeholder;

/**
 * The text forms a placeholder can be written in.
 *
 * All four name the same thing: a namespace, a key and its arguments. Which one
 * a consumer accepts is the consumer's business; the grammar reads any of them
 * into one {@link PlaceholderRef} and writes a ref back out in any of them.
 */
public enum PlaceholderSyntax {
	/** {@code %luna_tps_bar_10%}: PlaceholderAPI and TAB. */
	PERCENT,
	/** {@code <luna_tps_bar:10>}: a MiniMessage tag, arguments after the colon. */
	MINI_TAG,
	/** {@code {luna_tps_bar_10}}: PlaceholderAPI's bracket form and luna's own templates. */
	BRACE,
	/** {@code luna_tps_bar_10}: an identifier with no delimiters at all. */
	BARE
}
