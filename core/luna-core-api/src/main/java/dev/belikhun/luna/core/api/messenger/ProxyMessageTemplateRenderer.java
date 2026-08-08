package dev.belikhun.luna.core.api.messenger;

import java.util.Map;

public interface ProxyMessageTemplateRenderer {
	String renderTemplate(String template, Map<String, String> values);
}
