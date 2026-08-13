package dev.belikhun.luna.legacy.messenger;

import java.util.Map;

public interface ProxyMessageTemplateRenderer {
	String renderTemplate(String template, Map<String, String> values);
}
