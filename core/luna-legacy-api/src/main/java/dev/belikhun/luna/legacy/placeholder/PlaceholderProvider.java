package dev.belikhun.luna.legacy.placeholder;

import java.util.Map;

/**
 * One source of placeholder values.
 *
 * A provider may publish values eagerly into a snapshot, answer identifiers on
 * demand, or both. Returning null from {@link #resolve} passes the identifier to
 * the next provider for the same namespace.
 *
 * @param <S> the platform's placeholder service, which providers call back into
 *            for formatting and for the values only it can read
 * @param <P> the platform's player type
 */
public interface PlaceholderProvider<S, P> extends PlaceholderNamespaceProvider {
	/**
	 * Publishes the values this provider always has, before anything is asked for.
	 *
	 * @param values the map being filled, keyed by identifier without its namespace
	 */
	default void contributeSnapshot(
		S support,
		P player,
		PlaceholderSnapshot snapshot,
		Map<String, String> values
	) {
	}

	/**
	 * Answers one identifier.
	 *
	 * @param rawNamespace        the namespace as the caller wrote it
	 * @param normalizedNamespace the same namespace lowercased
	 * @param rawParams           everything after the namespace, as written
	 * @param normalizedParams    the same tail lowercased, for matching
	 * @return the value, or null to let the next provider try
	 */
	default String resolve(
		S support,
		P player,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams,
		PlaceholderSnapshot snapshot
	) {
		return null;
	}
}
