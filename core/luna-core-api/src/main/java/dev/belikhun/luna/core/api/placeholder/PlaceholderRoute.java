package dev.belikhun.luna.core.api.placeholder;

import java.util.List;

/** An identifier split into the providers that may answer it and its parts. */
public record PlaceholderRoute<T>(
	List<T> providers,
	String rawNamespace,
	String normalizedNamespace,
	String rawParams,
	String normalizedParams,
	/** The `_safe` variant, whose value has its percent signs escaped. */
	boolean safeVariant
) {
}
