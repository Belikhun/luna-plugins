package dev.belikhun.luna.legacy.tabbridge;

import java.util.Collections;
import java.util.Map;

/** No relational values at all; TAB leaves such an identifier as it found it. */
final class NoopTabBridgeRelationalPlaceholderSource<P> implements TabBridgeRelationalPlaceholderSource<P> {
	@Override
	public Map<String, Map<String, String>> resolve(P viewer) {
		return Collections.emptyMap();
	}
}
