package dev.belikhun.luna.core.api.placeholder;

import java.util.Set;

/**
 * The leading word a provider answers for, e.g. {@code luckperms} in
 * {@code %luckperms_prefix%}. An empty string claims identifiers with no
 * namespace at all.
 */
public interface PlaceholderNamespaceProvider {
	default Set<String> namespaces() {
		return Set.of("");
	}
}
