package dev.belikhun.luna.glyph.placeholder;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.glyph.BuildConstants;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;

import java.util.Map;

public final class GlyphMiniPlaceholders {
	private final LunaLogger logger;
	private Expansion expansion;

	public GlyphMiniPlaceholders(LunaLogger logger) {
		this.logger = logger.scope("MiniPlaceholders");
	}

	public void register(Map<String, String> glyphValues) {
		unregister();

		Expansion.Builder builder = Expansion.builder("lunaglyph")
			.author("Belikhun")
			.version(BuildConstants.VERSION);

		for (Map.Entry<String, String> entry : glyphValues.entrySet()) {
			builder.globalPlaceholder(entry.getKey(), (queue, context) -> textTag(entry.getValue()));
		}

		expansion = builder.build();
		expansion.register();
		logger.success("Đã đăng ký MiniPlaceholders namespace <lunaglyph>.");
	}

	public void unregister() {
		if (expansion == null) {
			return;
		}

		if (expansion.registered()) {
			expansion.unregister();
		}
		expansion = null;
	}

	private Tag textTag(String value) {
		if (value == null || value.isEmpty()) {
			return Tag.inserting(Component.empty());
		}
		return Tag.inserting(Component.text(value));
	}
}
