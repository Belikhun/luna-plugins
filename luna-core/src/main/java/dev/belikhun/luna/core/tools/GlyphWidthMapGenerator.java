package dev.belikhun.luna.core.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class GlyphWidthMapGenerator {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private GlyphWidthMapGenerator() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			throw new IllegalArgumentException("Expected args: <resourcesRoot> <outputFile>");
		}

		Path resourcesRoot = Path.of(args[0]);
		Path outputFile = Path.of(args[1]);

		Map<Integer, Integer> widths = buildFallbackGlyphWidths();
		loadFontDefinition(resourcesRoot, "font/default.json", widths, new java.util.HashSet<>());

		Files.createDirectories(outputFile.getParent());
		writeOutput(outputFile, widths);

		System.out.println("Generated glyph map: " + outputFile + " (entries=" + widths.size() + ")");
	}

	private static void writeOutput(Path outputFile, Map<Integer, Integer> widths) throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("generatedAt", Instant.now().toString());
		JsonObject mapObject = new JsonObject();

		Map<Integer, Integer> sorted = new TreeMap<>(widths);
		for (Map.Entry<Integer, Integer> entry : sorted.entrySet()) {
			mapObject.addProperty(String.valueOf(entry.getKey()), entry.getValue());
		}

		root.add("widths", mapObject);
		try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		}
	}

	private static void loadFontDefinition(Path resourcesRoot, String resourcePath, Map<Integer, Integer> widths, Set<String> visited) {
		if (!visited.add(resourcePath)) {
			return;
		}

		JsonObject root = readJson(resourcesRoot, resourcePath);
		if (root == null || !root.has("providers") || !root.get("providers").isJsonArray()) {
			return;
		}

		JsonArray providers = root.getAsJsonArray("providers");
		for (JsonElement element : providers) {
			if (!element.isJsonObject()) {
				continue;
			}

			JsonObject provider = element.getAsJsonObject();
			String type = readString(provider, "type");
			if (type.isBlank()) {
				continue;
			}

			switch (type) {
				case "reference" -> applyReference(resourcesRoot, provider, widths, visited);
				case "space" -> applySpaceProvider(provider, widths);
				case "bitmap" -> applyBitmapProvider(resourcesRoot, provider, widths);
				default -> {
				}
			}
		}
	}

	private static void applyReference(Path resourcesRoot, JsonObject provider, Map<Integer, Integer> widths, Set<String> visited) {
		String id = readString(provider, "id");
		String path = toResourcePath(id);
		if (path.isBlank()) {
			return;
		}

		loadFontDefinition(resourcesRoot, path, widths, visited);
	}

	private static void applySpaceProvider(JsonObject provider, Map<Integer, Integer> widths) {
		if (!provider.has("advances") || !provider.get("advances").isJsonObject()) {
			return;
		}

		JsonObject advances = provider.getAsJsonObject("advances");
		for (Map.Entry<String, JsonElement> entry : advances.entrySet()) {
			String glyph = entry.getKey();
			if (glyph == null || glyph.isEmpty()) {
				continue;
			}

			if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) {
				continue;
			}

			int codepoint = glyph.codePointAt(0);
			int advance = entry.getValue().getAsInt();
			widths.put(codepoint, advance);
		}
	}

	private static void applyBitmapProvider(Path resourcesRoot, JsonObject provider, Map<Integer, Integer> widths) {
		String file = readString(provider, "file");
		String imagePath = toResourcePath(file);
		if (imagePath.isBlank()) {
			return;
		}

		BufferedImage image = readImage(resourcesRoot, imagePath);
		if (image == null) {
			return;
		}

		if (!provider.has("chars") || !provider.get("chars").isJsonArray()) {
			return;
		}

		JsonArray rows = provider.getAsJsonArray("chars");
		int maxColumns = 0;
		for (JsonElement row : rows) {
			if (!row.isJsonPrimitive()) {
				continue;
			}

			String text = row.getAsString();
			maxColumns = Math.max(maxColumns, text.codePointCount(0, text.length()));
		}

		if (maxColumns <= 0 || rows.size() <= 0) {
			return;
		}

		int cellWidth = image.getWidth() / maxColumns;
		int cellHeight = image.getHeight() / rows.size();
		if (cellWidth <= 0 || cellHeight <= 0) {
			return;
		}

		for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
			JsonElement rowElement = rows.get(rowIndex);
			if (!rowElement.isJsonPrimitive()) {
				continue;
			}

			String row = rowElement.getAsString();
			int[] codepoints = row.codePoints().toArray();
			for (int colIndex = 0; colIndex < codepoints.length; colIndex++) {
				int codepoint = codepoints[colIndex];
				if (codepoint == 0) {
					continue;
				}

				int measured = measureBitmapGlyphWidth(image, colIndex * cellWidth, rowIndex * cellHeight, cellWidth, cellHeight);
				if (measured <= 0) {
					continue;
				}

				widths.put(codepoint, measured);
			}
		}
	}

	private static int measureBitmapGlyphWidth(BufferedImage image, int startX, int startY, int width, int height) {
		int rightMost = -1;
		for (int y = 0; y < height; y++) {
			for (int x = width - 1; x >= 0; x--) {
				int px = image.getRGB(startX + x, startY + y);
				int alpha = (px >>> 24) & 0xFF;
				if (alpha > 0) {
					rightMost = Math.max(rightMost, x);
					break;
				}
			}
		}

		if (rightMost < 0) {
			return 0;
		}

		return Math.max(1, rightMost + 1);
	}

	private static JsonObject readJson(Path resourcesRoot, String resourcePath) {
		Path path = resourcesRoot.resolve(resourcePath).normalize();
		if (!Files.exists(path)) {
			return null;
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, JsonObject.class);
		} catch (IOException | JsonParseException exception) {
			return null;
		}
	}

	private static BufferedImage readImage(Path resourcesRoot, String resourcePath) {
		Path primary = resourcesRoot.resolve(resourcePath).normalize();
		if (Files.exists(primary)) {
			try {
				return ImageIO.read(primary.toFile());
			} catch (IOException exception) {
				return null;
			}
		}

		String fallbackPath = toImageFallbackPath(resourcePath);
		if (!fallbackPath.isBlank()) {
			Path fallback = resourcesRoot.resolve(fallbackPath).normalize();
			if (Files.exists(fallback)) {
				try {
					return ImageIO.read(fallback.toFile());
				} catch (IOException exception) {
					return null;
				}
			}
		}

		return null;
	}

	private static String toImageFallbackPath(String resourcePath) {
		if (resourcePath == null || resourcePath.isBlank()) {
			return "";
		}

		if (resourcePath.startsWith("font/")) {
			return "textures/font/" + resourcePath.substring("font/".length());
		}

		return "";
	}

	private static String toResourcePath(String id) {
		String value = id == null ? "" : id.trim();
		if (value.isBlank()) {
			return "";
		}

		int separator = value.indexOf(':');
		if (separator >= 0 && separator < value.length() - 1) {
			value = value.substring(separator + 1);
		}

		if (value.startsWith("assets/minecraft/")) {
			value = value.substring("assets/minecraft/".length());
		}

		if (!value.startsWith("font/")) {
			value = "font/" + value;
		}

		if (!value.endsWith(".json") && !value.endsWith(".png")) {
			value = value + ".json";
		}

		if (value.startsWith("/")) {
			value = value.substring(1);
		}

		return value;
	}

	private static String readString(JsonObject object, String key) {
		if (object == null || key == null || !object.has(key)) {
			return "";
		}

		JsonElement element = object.get(key);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return "";
		}

		return element.getAsString();
	}

	private static Map<Integer, Integer> buildFallbackGlyphWidths() {
		Map<Integer, Integer> widths = new HashMap<>();
		for (char ch = 'A'; ch <= 'Z'; ch++) {
			widths.put((int) ch, 5);
		}
		for (char ch = 'a'; ch <= 'z'; ch++) {
			widths.put((int) ch, 5);
		}
		for (char ch = '0'; ch <= '9'; ch++) {
			widths.put((int) ch, 5);
		}

		widths.put((int) ' ', 3);
		widths.put((int) '!', 1);
		widths.put((int) '\'', 1);
		widths.put((int) ',', 1);
		widths.put((int) '.', 1);
		widths.put((int) ':', 1);
		widths.put((int) ';', 1);
		widths.put((int) '|', 1);
		widths.put((int) 'i', 1);
		widths.put((int) 'I', 3);
		widths.put((int) 'l', 2);
		widths.put((int) '[', 3);
		widths.put((int) ']', 3);
		widths.put((int) '(', 3);
		widths.put((int) ')', 3);
		widths.put((int) '{', 3);
		widths.put((int) '}', 3);
		widths.put((int) '"', 3);
		widths.put((int) '`', 2);
		widths.put((int) '*', 3);
		widths.put((int) 't', 4);
		widths.put((int) 'f', 4);
		widths.put((int) 'k', 4);
		widths.put((int) '<', 4);
		widths.put((int) '>', 4);
		widths.put((int) '@', 6);
		widths.put((int) '~', 6);
		widths.put((int) '%', 5);
		widths.put((int) '₫', 6);
		widths.put((int) '€', 6);
		widths.put((int) '$', 6);

		return widths;
	}
}
