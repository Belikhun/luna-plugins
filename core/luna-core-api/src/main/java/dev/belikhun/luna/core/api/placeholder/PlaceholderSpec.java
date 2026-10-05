package dev.belikhun.luna.core.api.placeholder;

import java.util.Locale;

/**
 * One placeholder, declared as data.
 *
 * Everything a consumer needs to know about a placeholder without resolving it
 * is here: where it lives, what it accepts, whose value it is, how often it
 * changes and what it means. The registry matches refs against the key pattern;
 * the engine calls the resolver.
 *
 * @param namespace   the leading word, lowercase: {@code luna}, {@code lunav}, {@code lunavault}
 * @param key         the key shape after the namespace
 * @param scope       whose value this is
 * @param refresh     how often the value can change
 * @param description one line for the generated reference and the debug listing
 * @param resolver    what computes the value
 * @param <P>         the platform's player type
 */
public record PlaceholderSpec<P>(
	String namespace,
	KeyPattern key,
	PlaceholderScope scope,
	PlaceholderRefresh refresh,
	String description,
	PlaceholderResolver<P> resolver
) {
	public PlaceholderSpec {
		if (namespace == null || namespace.isBlank()) {
			throw new IllegalArgumentException("A placeholder spec needs a namespace");
		}

		if (key == null) {
			throw new IllegalArgumentException("A placeholder spec needs a key pattern");
		}

		if (resolver == null) {
			throw new IllegalArgumentException("A placeholder spec needs a resolver: " + namespace + "_" + key);
		}

		namespace = namespace.trim().toLowerCase(Locale.ROOT);
		scope = scope == null ? PlaceholderScope.SERVER : scope;
		refresh = refresh == null ? PlaceholderRefresh.LIVE : refresh;
		description = description == null ? "" : description;
	}

	/** The canonical text, {@code namespace_keypattern}, for listings and collisions. */
	public String canonical() {
		return key.source().isEmpty() ? namespace : namespace + "_" + key.source();
	}

	/** Shorthand builder for a spec whose key is written as a pattern string. */
	public static <P> PlaceholderSpec<P> of(
		String namespace,
		String keyPattern,
		PlaceholderScope scope,
		PlaceholderRefresh refresh,
		String description,
		PlaceholderResolver<P> resolver
	) {
		return new PlaceholderSpec<>(namespace, KeyPattern.parse(keyPattern), scope, refresh, description, resolver);
	}
}
