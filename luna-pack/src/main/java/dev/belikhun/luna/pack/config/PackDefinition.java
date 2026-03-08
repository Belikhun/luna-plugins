package dev.belikhun.luna.pack.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record PackDefinition(
	String name,
	String filename,
	int priority,
	boolean required,
	boolean enabled,
	List<String> servers,
	Path sourceFile
) {
	public String normalizedName() {
		return name.toLowerCase(Locale.ROOT);
	}

	public boolean matchesServer(String serverName) {
		String normalized = serverName == null ? "" : serverName.toLowerCase(Locale.ROOT);
		for (String server : servers) {
			if (server.equals("*") || server.equals("all") || server.equals(normalized)) {
				return true;
			}
		}
		return false;
	}
}
