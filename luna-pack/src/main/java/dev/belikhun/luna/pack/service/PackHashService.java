package dev.belikhun.luna.pack.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.config.LoaderConfig;
import dev.belikhun.luna.pack.config.PackDefinition;
import dev.belikhun.luna.pack.model.ResolvedPack;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class PackHashService {
	private final LunaLogger logger;

	public PackHashService(LunaLogger logger) {
		this.logger = logger.scope("PackHash");
	}

	public List<ResolvedPack> resolveAll(LoaderConfig config, Iterable<PackDefinition> definitions) {
		List<ResolvedPack> resolved = new ArrayList<>();
		for (PackDefinition definition : definitions) {
			resolved.add(resolveOne(config, definition));
		}
		return resolved;
	}

	private ResolvedPack resolveOne(LoaderConfig config, PackDefinition definition) {
		URI url = toUri(config.baseUrl(), definition.filename());
		if (url == null) {
			return new ResolvedPack(definition, URI.create("https://invalid.invalid"), "", 0L, false, "INVALID_URL");
		}

		Path filePath = config.packPath().resolve(definition.filename()).normalize();
		if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
			logger.warn("Không tìm thấy file pack " + definition.filename() + " tại " + filePath);
			return new ResolvedPack(definition, url, "", 0L, false, "MISSING_FILE");
		}

		try {
			long size = Files.size(filePath);
			String hash = sha1Hex(filePath);
			return new ResolvedPack(definition, url, hash, size, true, "");
		} catch (IOException exception) {
			logger.error("Không thể đọc file pack " + filePath, exception);
			return new ResolvedPack(definition, url, "", 0L, false, "READ_FAILED");
		}
	}

	private URI toUri(String baseUrl, String filename) {
		String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
		try {
			return new URI(normalized + filename);
		} catch (URISyntaxException exception) {
			logger.warn("URL pack không hợp lệ: " + normalized + filename);
			return null;
		}
	}

	private String sha1Hex(Path path) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-1 algorithm không khả dụng", exception);
		}

		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest()).toLowerCase();
	}
}
