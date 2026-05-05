package dev.belikhun.luna.core.neoforge.placeholder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class NeoForgePlaceholderRouting {
	private static final String SAFE_SUFFIX = "_safe";

	private NeoForgePlaceholderRouting() {
	}

	static <T extends NeoForgePlaceholderNamespaceProvider> Map<String, List<T>> indexProvidersByNamespace(List<T> providers) {
		Map<String, List<T>> indexed = new LinkedHashMap<>();
		Set<String> seenExplicitNamespaces = new LinkedHashSet<>();
		for (T provider : providers) {
			Set<String> namespaces = provider == null ? Set.of() : provider.namespaces();
			if (namespaces == null || namespaces.isEmpty()) {
				indexed.computeIfAbsent("", ignored -> new ArrayList<>()).add(provider);
				continue;
			}

			for (String namespace : namespaces) {
				String normalizedNamespace = normalizeNamespace(namespace);
				if (!normalizedNamespace.isEmpty() && !seenExplicitNamespaces.add(normalizedNamespace)) {
					throw new IllegalArgumentException("NeoForge placeholder namespace bị trùng: " + normalizedNamespace);
				}
				indexed.computeIfAbsent(normalizedNamespace, ignored -> new ArrayList<>()).add(provider);
			}
		}

		Map<String, List<T>> immutable = new LinkedHashMap<>();
		for (Map.Entry<String, List<T>> entry : indexed.entrySet()) {
			immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		return Map.copyOf(immutable);
	}

	static <T> NeoForgePlaceholderRoute<T> resolve(String identifier, Map<String, List<T>> providersByNamespace) {
		String rawIdentifier = unwrapIdentifier(identifier);
		if (rawIdentifier.isBlank()) {
			return null;
		}

		String normalizedIdentifier = rawIdentifier.toLowerCase(Locale.ROOT);
		int separator = rawIdentifier.indexOf('_');
		if (separator > 0) {
			String rawNamespace = rawIdentifier.substring(0, separator);
			String normalizedNamespace = normalizedIdentifier.substring(0, separator);
			List<T> namespacedProviders = providersByNamespace.get(normalizedNamespace);
			if (namespacedProviders != null && !namespacedProviders.isEmpty()) {
				return route(namespacedProviders, rawNamespace, normalizedNamespace, rawIdentifier.substring(separator + 1), normalizedIdentifier.substring(separator + 1));
			}
		}

		List<T> defaultProviders = providersByNamespace.get("");
		if (defaultProviders == null || defaultProviders.isEmpty()) {
			return null;
		}

		return route(defaultProviders, "", "", rawIdentifier, normalizedIdentifier);
	}

	private static <T> NeoForgePlaceholderRoute<T> route(
		List<T> providers,
		String rawNamespace,
		String normalizedNamespace,
		String rawParams,
		String normalizedParams
	) {
		boolean safeVariant = "luna".equals(normalizedNamespace)
			&& normalizedParams.endsWith(SAFE_SUFFIX)
			&& normalizedParams.length() > SAFE_SUFFIX.length();
		if (!safeVariant) {
			return new NeoForgePlaceholderRoute<>(providers, rawNamespace, normalizedNamespace, rawParams, normalizedParams, false);
		}

		return new NeoForgePlaceholderRoute<>(
			providers,
			rawNamespace,
			normalizedNamespace,
			rawParams.substring(0, rawParams.length() - SAFE_SUFFIX.length()),
			normalizedParams.substring(0, normalizedParams.length() - SAFE_SUFFIX.length()),
			true
		);
	}

	static String unwrapIdentifier(String identifier) {
		if (identifier == null) {
			return "";
		}

		String trimmed = identifier.trim();
		if (trimmed.isEmpty()) {
			return "";
		}

		if (trimmed.startsWith("%") && trimmed.endsWith("%") && trimmed.length() >= 3) {
			return trimmed.substring(1, trimmed.length() - 1);
		}

		return trimmed;
	}

	private static String normalizeNamespace(String namespace) {
		return namespace == null ? "" : namespace.trim().toLowerCase(Locale.ROOT);
	}
}
