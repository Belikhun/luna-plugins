package dev.belikhun.luna.glyph.placeholder;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.glyph.BuildConstants;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;

import java.util.Locale;
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

	public void register(Function<String, String> glyphResolver) {
		unregister();
		this.glyphResolver = Objects.requireNonNullElse(glyphResolver, key -> null);

		Expansion.Builder builder = Expansion.builder("lunaglyph")
			.author("Belikhun")
			.version(BuildConstants.VERSION)
			.globalPlaceholder("glyph", (queue, context) -> resolveGlyphTag(queue));

		expansion = builder.build();
		expansion.register();
		logger.success("Đã đăng ký MiniPlaceholders namespace <lunaglyph> với resolver động <lunaglyph:glyph:key>.");
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
