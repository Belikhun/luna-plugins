package dev.belikhun.luna.glyph.placeholder;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRefresh;
import dev.belikhun.luna.core.api.placeholder.PlaceholderScope;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSpec;
import dev.belikhun.luna.core.velocity.placeholder.VelocityPlaceholderModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code %lunav_glyph_<key>%}: a glyph from {@code glyphs.yml} by key.
 *
 * One spec with a parameter answers every key, so a reload swaps the map and
 * re-enumerates the TAB ids without touching the registry. The pre-rename
 * {@code %lunaglyph-<key>%} ids stay registered as aliases.
 */
public final class GlyphPlaceholders {
	private final AtomicReference<Map<String, String>> glyphs = new AtomicReference<>(Map.of());
	private boolean registered;

	/** Publishes the spec once and points TAB at the current glyph keys. */
	public void register(VelocityPlaceholderModule module, Map<String, String> glyphValues) {
		update(glyphValues);

		if (!registered) {
			registered = true;
			module.enumerateTabIds(this::tabIds);
			module.runtime().register(this, List.of(
				PlaceholderSpec.of("lunav", "glyph_<key:word>", PlaceholderScope.SERVER, PlaceholderRefresh.STATIC,
					"A glyph from glyphs.yml; the key is the argument",
					request -> glyphs.get().get(normalize(request.param("key"))))
			));
		}

		for (String key : glyphs.get().keySet()) {
			module.aliasTabId("%lunaglyph-" + key + "%", "%lunav_glyph_" + key + "%");
		}

		module.syncAdapters();
	}

	public void unregister(VelocityPlaceholderModule module) {
		module.runtime().unregister(this);
		glyphs.set(Map.of());
		registered = false;
	}

	private void update(Map<String, String> glyphValues) {
		Map<String, String> normalized = new java.util.LinkedHashMap<>();

		for (Map.Entry<String, String> entry : Objects.requireNonNullElse(glyphValues, Map.<String, String>of()).entrySet()) {
			String key = normalize(entry.getKey());

			if (!key.isBlank() && entry.getValue() != null) {
				normalized.put(key, entry.getValue());
			}
		}

		glyphs.set(Map.copyOf(normalized));
	}

	private List<String> tabIds() {
		List<String> ids = new ArrayList<>();

		for (String key : glyphs.get().keySet()) {
			ids.add("%lunav_glyph_" + key + "%");
		}

		return ids;
	}

	private static String normalize(String key) {
		return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
	}
}
