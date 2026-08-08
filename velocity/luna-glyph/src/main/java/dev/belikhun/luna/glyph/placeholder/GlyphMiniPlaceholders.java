package dev.belikhun.luna.glyph.placeholder;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.glyph.BuildConstants;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class GlyphMiniPlaceholders {
	private final LunaLogger logger;
	private Expansion expansion;
	private Function<String, String> glyphResolver;

	public GlyphMiniPlaceholders(LunaLogger logger) {
		this.logger = logger.scope("MiniPlaceholders");
		this.glyphResolver = key -> null;
	}

	public void register(Map<String, String> glyphValues) {
		unregister();
		Map<String, String> resolvedGlyphValues = Objects.requireNonNullElse(glyphValues, Map.of());
		this.glyphResolver = key -> resolvedGlyphValues.get(normalizeKey(key));

		Expansion.Builder builder = Expansion.builder("lunaglyph")
			.author("Belikhun")
			.version(BuildConstants.VERSION)
			.globalPlaceholder("glyph", (queue, context) -> resolveGlyphTag(queue));

		for (String key : new LinkedHashSet<>(resolvedGlyphValues.keySet())) {
			String normalizedKey = normalizeKey(key);
			if (normalizedKey.isBlank()) {
				continue;
			}

			builder.globalPlaceholder("glyph:" + normalizedKey, (queue, context) -> textTag(this.glyphResolver.apply(normalizedKey)));
		}

		expansion = builder.build();
		expansion.register();
		logger.success("Đã đăng ký MiniPlaceholders namespace <lunaglyph> với resolver động <lunaglyph:glyph:key> và alias <lunaglyph:glyph key>.");
	}

	public void unregister() {
		if (expansion == null) {
			return;
		}

		if (expansion.registered()) {
			expansion.unregister();
		}
		expansion = null;
		glyphResolver = key -> null;
	}

	private Tag resolveGlyphTag(ArgumentQueue queue) {
		if (!queue.hasNext()) {
			return Tag.inserting(Component.empty());
		}

		String rawKey = queue.pop().value();
		String key = normalizeKey(rawKey);
		if (key.isBlank()) {
			return Tag.inserting(Component.empty());
		}

		String value = glyphResolver.apply(key);
		return textTag(value);
	}

	private String normalizeKey(String key) {
		if (key == null) {
			return "";
		}
		return key.trim().toLowerCase(Locale.ROOT);
	}

	private Tag textTag(String value) {
		if (value == null || value.isEmpty()) {
			return Tag.inserting(Component.empty());
		}
		return Tag.inserting(Component.text(value));
	}
}
