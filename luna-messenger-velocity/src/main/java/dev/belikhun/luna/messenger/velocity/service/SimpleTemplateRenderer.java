package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.messenger.ProxyMessageTemplateRenderer;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;

public final class SimpleTemplateRenderer implements ProxyMessageTemplateRenderer {
	private static final MiniMessage MM = MiniMessage.miniMessage();

	@Override
	public String renderTemplate(String template, Map<String, String> values) {
		String output = template == null ? "" : template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String percentPlaceholder = "%" + entry.getKey() + "%";
			String replacement = MM.escapeTags(entry.getValue() == null ? "" : entry.getValue());
			output = output.replace(percentPlaceholder, replacement);
		}
		return output;
	}
}
