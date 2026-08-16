package dev.belikhun.luna.pack.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.config.LoaderConfig;
import dev.belikhun.luna.pack.config.PackDefinition;
import dev.belikhun.luna.pack.model.PackFormatRange;
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
	private final PackMetaService metaService;

	public PackHashService(LunaLogger logger) {
		this.logger = logger.scope("PackHash");
		this.metaService = new PackMetaService(logger);
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
			return new ResolvedPack(definition, URI.create("https://invalid.invalid"), "", 0L, false, "INVALID_URL", null);
		}

		Path filePath = config.packPath().resolve(definition.filename()).normalize();
		if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
			logger.warn("Không tìm thấy file pack " + definition.filename() + " tại " + filePath);
			return new ResolvedPack(definition, url, "", 0L, false, "MISSING_FILE", null);
		}

		try {
			long size = Files.size(filePath);
			String hash = sha1Hex(filePath);
			PackFormatRange range = metaService.readRange(filePath);
			reportRange(definition, range);
			return new ResolvedPack(definition, url, hash, size, true, "", range);
		} catch (IOException exception) {
			logger.error("Không thể đọc file pack " + filePath, exception);
			return new ResolvedPack(definition, url, "", 0L, false, "READ_FAILED", null);
		}
	}

	private void reportRange(PackDefinition definition, PackFormatRange range) {
		if (range == null) {
			logger.warn("Pack " + definition.name() + " không khai báo khoảng định dạng; sẽ gửi cho mọi phiên bản client.");
			return;
		}

		if (range.isEmpty()) {
			logger.warn("Pack " + definition.name() + " khai báo khoảng định dạng rỗng (" + range.render()
				+ " sau khi giới hạn); sẽ không gửi cho client nào. Thêm min_format/max_format vào pack.mcmeta.");
			return;
		}

		if (range.clamped()) {
			logger.warn("Pack " + definition.name() + " chỉ khai báo định dạng kiểu cũ vượt quá 64;"
				+ " client từ 1.21.9 sẽ từ chối file này nên khoảng hiệu lực bị giới hạn còn " + range.render()
				+ ". Thêm min_format/max_format vào pack.mcmeta để phục vụ client mới.");
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
