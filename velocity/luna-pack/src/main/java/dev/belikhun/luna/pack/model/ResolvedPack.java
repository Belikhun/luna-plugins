package dev.belikhun.luna.pack.model;

import dev.belikhun.luna.pack.config.PackDefinition;

import java.net.URI;

public record ResolvedPack(
	PackDefinition definition,
	URI url,
	String sha1,
	long sizeBytes,
	boolean available,
	String unavailableReason,
	/** Declared format range from the zip's pack.mcmeta; null = undeclared, never filtered */
	PackFormatRange formatRange
) {
	public boolean loadableBy(PackFormat clientFormat) {
		if (formatRange == null || clientFormat == null) {
			return true;
		}
		return formatRange.contains(clientFormat);
	}
}
