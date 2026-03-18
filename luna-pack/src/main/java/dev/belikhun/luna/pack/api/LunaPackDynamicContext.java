package dev.belikhun.luna.pack.api;

import java.nio.file.Path;

public record LunaPackDynamicContext(
	String baseUrl,
	Path packPath
) {
}
