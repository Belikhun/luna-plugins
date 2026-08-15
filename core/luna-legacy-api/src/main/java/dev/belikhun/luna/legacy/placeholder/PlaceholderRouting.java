package dev.belikhun.luna.legacy.placeholder;

import dev.belikhun.luna.legacy.string.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which providers get asked about an identifier.
 *
 * An identifier is split at its first underscore: the head is a namespace if a
 * provider claims it, and everything else goes to the providers registered under
 * the empty namespace. That is why {@code %player_name%} reaches the built-ins
 * while {@code %luckperms_prefix%} does not - nothing claims {@code player}.
 */
public final class PlaceholderRouting {
	private static final String SAFE_SUFFIX = "_safe";

	private PlaceholderRouting() {
	}

	/**
	 * Groups providers by the namespaces they claim, keeping registration order
	 * within each namespace so the first one registered answers first.
	 *
	 * @param providers the providers in the order they should be asked
	 * @return an immutable index, with the empty key holding the default providers
	 */
	public static <T extends PlaceholderNamespaceProvider> Map<String, List<T>> indexProvidersByNamespace(List<T> providers) {
		Map<String, List<T>> indexed = new LinkedHashMap<>();

		for (T provider : providers) {
			Set<String> namespaces = provider == null ? Collections.emptySet() : provider.namespaces();

			if (namespaces == null || namespaces.isEmpty()) {
				indexed.computeIfAbsent("", ignored -> new ArrayList<>()).add(provider);
				continue;
			}

			Set<String> normalizedNamespaces = new LinkedHashSet<>();

			for (String namespace : namespaces) {
				String normalizedNamespace = normalizeNamespace(namespace);

				// a provider listing the same namespace twice must not be asked twice
				if (!normalizedNamespaces.add(normalizedNamespace)) {
					continue;
				}

				indexed.computeIfAbsent(normalizedNamespace, ignored -> new ArrayList<>()).add(provider);
			}
		}

		Map<String, List<T>> immutable = new LinkedHashMap<>();

		for (Map.Entry<String, List<T>> entry : indexed.entrySet()) {
			immutable.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<T>(entry.getValue())));
		}

		return Collections.unmodifiableMap(immutable);
	}

	/**
	 * Splits an identifier and picks the providers that may answer it.
	 *
	 * @param identifier          written either bare or wrapped in percent signs
	 * @param providersByNamespace the index from {@link #indexProvidersByNamespace}
	 * @return the route, or null when nothing claims the identifier
	 */
	public static <T> PlaceholderRoute<T> resolve(String identifier, Map<String, List<T>> providersByNamespace) {
		String rawIdentifier = unwrapIdentifier(identifier);

		if (Strings.isBlank(rawIdentifier)) {
			return null;
		}

		String normalizedIdentifier = rawIdentifier.toLowerCase(Locale.ROOT);
		int separator = rawIdentifier.indexOf('_');

		if (separator > 0) {
			String normalizedNamespace = normalizedIdentifier.substring(0, separator);
			List<T> namespacedProviders = providersByNamespace.get(normalizedNamespace);

			if (namespacedProviders != null && !namespacedProviders.isEmpty()) {
				return route(
					namespacedProviders,
					rawIdentifier.substring(0, separator),
					normalizedNamespace,
					rawIdentifier.substring(separator + 1),
					normalizedIdentifier.substring(separator + 1)
				);
			}
		}

		List<T> defaultProviders = providersByNamespace.get("");

		if (defaultProviders == null || defaultProviders.isEmpty()) {
			return null;
		}

		return route(defaultProviders, "", "", rawIdentifier, normalizedIdentifier);
	}

	private static <T> PlaceholderRoute<T> route(
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
			return new PlaceholderRoute<>(providers, rawNamespace, normalizedNamespace, rawParams, normalizedParams, false);
		}

		return new PlaceholderRoute<>(
			providers,
			rawNamespace,
			normalizedNamespace,
			rawParams.substring(0, rawParams.length() - SAFE_SUFFIX.length()),
			normalizedParams.substring(0, normalizedParams.length() - SAFE_SUFFIX.length()),
			true
		);
	}

	/**
	 * Accepts an identifier written either bare or wrapped in percent signs.
	 *
	 * @return the bare identifier, empty when there is nothing to read
	 */
	public static String unwrapIdentifier(String identifier) {
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
