package dev.belikhun.luna.pack.model;

import dev.belikhun.luna.pack.config.PackDefinition;

import java.net.URI;

public record ResolvedPack(
	PackDefinition definition,
	URI url,
	String sha1,
	long sizeBytes,
	boolean available,
	String unavailableReason
) {
}
