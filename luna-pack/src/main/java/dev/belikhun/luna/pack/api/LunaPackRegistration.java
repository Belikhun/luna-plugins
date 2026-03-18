package dev.belikhun.luna.pack.api;

import java.util.List;

public record LunaPackRegistration(
	String name,
	String filename,
	int priority,
	boolean required,
	boolean enabled,
	List<String> servers
) {
}
