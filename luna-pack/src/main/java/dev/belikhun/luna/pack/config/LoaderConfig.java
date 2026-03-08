package dev.belikhun.luna.pack.config;

import java.nio.file.Path;

public record LoaderConfig(
	String baseUrl,
	Path packPath
) {
}
