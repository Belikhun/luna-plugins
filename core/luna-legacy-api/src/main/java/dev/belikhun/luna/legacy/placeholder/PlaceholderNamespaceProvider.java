package dev.belikhun.luna.legacy.placeholder;

import java.util.Collections;
import java.util.Set;

/**
 * The leading word a provider answers for, e.g. {@code luckperms} in
 * {@code %luckperms_prefix%}. An empty string claims identifiers with no
 * namespace at all.
 */
public interface PlaceholderNamespaceProvider {
	default Set<String> namespaces() {
		return Collections.singleton("");
	}
}
