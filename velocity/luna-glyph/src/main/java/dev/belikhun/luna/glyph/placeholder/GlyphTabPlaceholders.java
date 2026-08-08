package dev.belikhun.luna.glyph.placeholder;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.placeholder.PlaceholderManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GlyphTabPlaceholders {
	private static final int REFRESH_INTERVAL = 10000;

	private final LunaLogger logger;
	private final List<String> registeredPlaceholders;

	public GlyphTabPlaceholders(LunaLogger logger) {
		this.logger = logger.scope("TAB");
		this.registeredPlaceholders = new ArrayList<>();
	}

	public void register(Map<String, String> glyphValues) {
		unregister();

		PlaceholderManager manager = TabAPI.getInstance().getPlaceholderManager();
		for (Map.Entry<String, String> entry : glyphValues.entrySet()) {
			String identifier = "%lunaglyph-" + entry.getKey() + "%";
			String value = entry.getValue();
			manager.registerServerPlaceholder(identifier, REFRESH_INTERVAL, () -> value);
			registeredPlaceholders.add(identifier);
		}

		logger.success("Đã đăng ký TAB placeholders nhóm %lunaglyph-*%.");
	}

	public void unregister() {
		if (registeredPlaceholders.isEmpty()) {
			return;
		}

		try {
			PlaceholderManager manager = TabAPI.getInstance().getPlaceholderManager();
			for (String identifier : registeredPlaceholders) {
				manager.unregisterPlaceholder(identifier);
			}
		} catch (Throwable ignored) {
		}

		registeredPlaceholders.clear();
	}
}
