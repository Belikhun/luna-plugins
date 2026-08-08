package dev.belikhun.luna.glyph.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.glyph.config.GlyphPluginState;
import dev.belikhun.luna.glyph.model.GlyphDefinition;
import dev.belikhun.luna.glyph.model.GlyphType;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class GlyphPackBuilder {
	private static final int ICON_PROVIDER_HEIGHT = 8;
	private static final int ICON_PROVIDER_ASCENT = 7;
	private static final int MAX_GLYPH_SIZE = 256;

	private final LunaLogger logger;

	public GlyphPackBuilder(LunaLogger logger) {
		this.logger = logger.scope("PackBuilder");
	}

	public BuildResult build(GlyphPluginState state, Path outputZip, Path iconPath, Path glyphsDirectory) {
		logger.audit("Bắt đầu build pack glyph. output=" + outputZip + ", glyphCount=" + state.glyphs().size());
		if (state.glyphs().isEmpty()) {
			logger.warn("Không có glyph nào trong state, dừng build.");
			return new BuildResult(false, 0, "Không có glyph nào để build.");
		}

		Path tempDir;
		try {
			tempDir = Files.createTempDirectory("lunaglyph_pack_");
			logger.audit("Thư mục tạm build: " + tempDir);
		} catch (IOException exception) {
			logger.error("Không thể tạo thư mục tạm build glyph.", exception);
			return new BuildResult(false, 0, "Không thể tạo thư mục tạm.");
		}

		int builtGlyphs = 0;
		try {
			Path fontsDir = tempDir.resolve("assets").resolve("minecraft").resolve("font");
			Path texturesDir = tempDir.resolve("assets").resolve(state.pack().namespace()).resolve("textures").resolve("font");
			Files.createDirectories(fontsDir);
			Files.createDirectories(texturesDir);
			logger.audit("Đã chuẩn bị thư mục font=" + fontsDir + " và textures=" + texturesDir);

			List<String> providers = new ArrayList<>();
			int index = 0;
			for (GlyphDefinition glyph : state.glyphs().values()) {
				Path source = glyphsDirectory.resolve(glyph.fileName()).normalize();
				logger.audit("Xử lý glyph '" + glyph.name() + "' từ file " + glyph.fileName());
				if (!Files.exists(source) || !Files.isRegularFile(source)) {
					logger.warn("Thiếu file glyph: " + source);
					return new BuildResult(false, builtGlyphs, "Thiếu file glyph: " + glyph.fileName());
				}

				BufferedImage image = ImageIO.read(source.toFile());
				if (image == null) {
					logger.warn("Không đọc được ảnh glyph: " + source);
					return new BuildResult(false, builtGlyphs, "Không thể đọc ảnh glyph: " + glyph.fileName());
				}

				RenderSpec renderSpec = computeRenderSpec(glyph, image);
				BufferedImage rendered = resize(image, renderSpec.width(), renderSpec.height());

				Path texturePath = texturesDir.resolve(index + ".png");
				ImageIO.write(rendered, "PNG", texturePath.toFile());
				logger.audit("Glyph '" + glyph.name() + "' render " + renderSpec.width() + "x" + renderSpec.height() + " -> " + texturePath.getFileName());
				providers.add(providerJson(state.pack().namespace(), index, renderSpec.providerAscent(), renderSpec.providerHeight(), glyph.character()));
				builtGlyphs++;
				index++;
			}

			providers.add(referenceProvider("minecraft:include/space", null));
			providers.add(referenceProvider("minecraft:include/default", "{\"uniform\":false}"));
			providers.add(referenceProvider("minecraft:include/unifont", null));

			String defaultJson = "{\n  \"providers\": [\n    " + String.join(",\n    ", providers) + "\n  ]\n}";
			Files.writeString(fontsDir.resolve("default.json"), defaultJson, StandardCharsets.UTF_8);
			logger.audit("Đã ghi default.json với " + providers.size() + " providers.");

			String packMeta = packMetaJson(state.pack().description());
			Files.writeString(tempDir.resolve("pack.mcmeta"), packMeta, StandardCharsets.UTF_8);
			logger.audit("Đã ghi pack.mcmeta.");

			if (Files.exists(iconPath) && Files.isRegularFile(iconPath)) {
				try {
					BufferedImage icon = ImageIO.read(iconPath.toFile());
					if (icon != null) {
						ImageIO.write(icon, "PNG", tempDir.resolve("pack.png").toFile());
						logger.audit("Đã nhúng pack icon từ " + iconPath.getFileName());
					}
				} catch (IOException exception) {
					logger.warn("Không thể đọc icon.png, bỏ qua pack icon.");
				}
			}

			Files.createDirectories(outputZip.getParent());
			zipDirectory(tempDir, outputZip);
			logger.success("Build pack glyph thành công: " + builtGlyphs + " glyph -> " + outputZip);
			return new BuildResult(true, builtGlyphs, "");
		} catch (IOException exception) {
			logger.error("Không thể build resource pack glyph.", exception);
			return new BuildResult(false, builtGlyphs, "Lỗi ghi file resource pack.");
		} finally {
			deleteRecursively(tempDir);
		}
	}

	private RenderSpec computeRenderSpec(GlyphDefinition glyph, BufferedImage image) {
		int width = glyph.width() == null ? -1 : glyph.width();
		int height = glyph.height() == null ? -1 : glyph.height();
		int providerHeight;
		int providerAscent;

		if (glyph.type() == GlyphType.ICON) {
			if (width < 1 && height < 1) {
				width = ICON_PROVIDER_HEIGHT;
				height = ICON_PROVIDER_HEIGHT;
			} else if (width < 1) {
				width = scaleByAspect(height, image.getHeight(), image.getWidth());
			} else if (height < 1) {
				height = scaleByAspect(width, image.getWidth(), image.getHeight());
			}
			providerHeight = ICON_PROVIDER_HEIGHT;
			providerAscent = ICON_PROVIDER_ASCENT;
		} else {
			if (width < 1 && height < 1) {
				width = image.getWidth();
				height = image.getHeight();
			} else if (width < 1) {
				width = scaleByAspect(height, image.getHeight(), image.getWidth());
			} else if (height < 1) {
				height = scaleByAspect(width, image.getWidth(), image.getHeight());
			}
			providerHeight = clamp(height, 1, MAX_GLYPH_SIZE);
			providerAscent = Math.max(0, providerHeight - 1);
		}

		width = clamp(width, 1, MAX_GLYPH_SIZE);
		height = clamp(height, 1, MAX_GLYPH_SIZE);
		return new RenderSpec(width, height, providerHeight, providerAscent);
	}

	private int scaleByAspect(int targetPrimary, int sourcePrimary, int sourceSecondary) {
		if (sourcePrimary <= 0 || sourceSecondary <= 0) {
			return targetPrimary;
		}

		double ratio = (double) targetPrimary * (double) sourceSecondary / (double) sourcePrimary;
		return Math.max(1, (int) Math.round(ratio));
	}

	private BufferedImage resize(BufferedImage source, int width, int height) {
		if (source.getWidth() == width && source.getHeight() == height) {
			return source;
		}

		BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = output.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		graphics.drawImage(source, 0, 0, width, height, null);
		graphics.dispose();
		return output;
	}

	private String providerJson(String namespace, int index, int ascent, int height, String character) {
		return "{\"type\":\"bitmap\",\"file\":\""
			+ escapeJson(namespace)
			+ ":font/"
			+ index
			+ ".png\",\"ascent\":"
			+ ascent
			+ ",\"height\":"
			+ height
			+ ",\"chars\":[\""
			+ escapeJson(character)
			+ "\"]}";
	}

	private String referenceProvider(String id, String filterJson) {
		if (filterJson == null) {
			return "{\"type\":\"reference\",\"id\":\"" + escapeJson(id) + "\"}";
		}
		return "{\"type\":\"reference\",\"id\":\"" + escapeJson(id) + "\",\"filter\":" + filterJson + "}";
	}

	private String packMetaJson(String description) {
		String safeDescription = description == null ? "Luna Glyph Resource Pack" : description;
		return "{\n"
			+ "  \"pack\": {\n"
			+ "    \"description\": \""
			+ escapeJson(safeDescription)
			+ "\",\n"
			+ "    \"pack_format\": 64,\n"
			+ "    \"supported_formats\": [1, 64],\n"
			+ "    \"min_format\": 1,\n"
			+ "    \"max_format\": 75\n"
			+ "  }\n"
			+ "}";
	}

	private void zipDirectory(Path sourceDir, Path outputZip) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputZip))) {
			try (var stream = Files.walk(sourceDir)) {
				stream.filter(Files::isRegularFile).forEach(path -> {
					String entryName = sourceDir.relativize(path).toString().replace('\\', '/');
					ZipEntry entry = new ZipEntry(entryName);
					try {
						zip.putNextEntry(entry);
						Files.copy(path, zip);
						zip.closeEntry();
					} catch (IOException exception) {
						throw new RuntimeException(exception);
					}
				});
			} catch (RuntimeException runtimeException) {
				if (runtimeException.getCause() instanceof IOException ioException) {
					throw ioException;
				}
				throw runtimeException;
			}
		}
	}

	private void deleteRecursively(Path path) {
		if (path == null || !Files.exists(path)) {
			return;
		}

		try (var stream = Files.walk(path)) {
			stream.sorted((a, b) -> b.compareTo(a)).forEach(target -> {
				try {
					Files.deleteIfExists(target);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private String escapeJson(String value) {
		StringBuilder output = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			switch (ch) {
				case '\\' -> output.append("\\\\");
				case '"' -> output.append("\\\"");
				case '\n' -> output.append("\\n");
				case '\r' -> output.append("\\r");
				case '\t' -> output.append("\\t");
				default -> {
					if (ch < 0x20) {
						output.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
					} else {
						output.append(ch);
					}
				}
			}
		}
		return output.toString();
	}

	private record RenderSpec(int width, int height, int providerHeight, int providerAscent) {
	}

	public record BuildResult(boolean success, int generatedGlyphs, String errorMessage) {
	}
}
