package dev.belikhun.luna.core.neoforge.placeholder;

import java.util.Set;

interface NeoForgePlaceholderNamespaceProvider {
	default Set<String> namespaces() {
		return Set.of("");
	}
}
