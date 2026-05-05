package dev.belikhun.luna.core.neoforge.placeholder;

import java.util.List;

record NeoForgePlaceholderRoute<T>(
	List<T> providers,
	String rawNamespace,
	String normalizedNamespace,
	String rawParams,
	String normalizedParams,
	boolean safeVariant
) {
}
