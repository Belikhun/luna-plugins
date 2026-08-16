package dev.belikhun.luna.pack.config;

import dev.belikhun.luna.pack.model.PackFormat;

import java.nio.file.Path;
import java.util.Map;

public record LoaderConfig(
	String baseUrl,
	Path packPath,
	/** Withhold packs whose declared format range excludes the client's version */
	boolean versionFilter,
	/** Operator additions to the protocol → pack format table, over the built-ins */
	Map<Integer, PackFormat> clientFormats
) {
	public LoaderConfig(String baseUrl, Path packPath) {
		this(baseUrl, packPath, true, Map.of());
	}
}
