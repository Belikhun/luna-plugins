package dev.belikhun.luna.pack.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.model.PackFormat;
import dev.belikhun.luna.pack.model.PackFormatRange;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the format range a pack declares in its own pack.mcmeta, so selection
 * can withhold a pack from clients whose version cannot load it.
 *
 * Normalization order mirrors the client's:
 * - min_format/max_format (1.21.9+) win when both are present.
 * - Otherwise supported_formats (int, [min, max] pair, or
 *   {min_inclusive, max_inclusive}), falling back to pack_format for a
 *   missing bound.
 * - Otherwise pack_format alone declares exactly one format.
 *
 * A legacy declaration reaching past format 64 is clamped there: clients from
 * 1.21.9 refuse the file wholesale when the legacy fields alone claim anything
 * newer ("missing mandatory fields min_format and max_format"), so past 64 the
 * claim is not just optimistic, it is unloadable.
 */
public final class PackMetaService {
	private static final String MCMETA_ENTRY = "pack.mcmeta";

	/** pack.mcmeta is a manifest; anything bigger is not one we should parse. */
	private static final long MAX_MCMETA_BYTES = 256 * 1024;

	private final LunaLogger logger;

	public PackMetaService(LunaLogger logger) {
		this.logger = logger.scope("PackMeta");
	}

	/**
	 * The declared format range of the zip at `path`, or null when the zip has
	 * no readable declaration (no pack.mcmeta, unparsable JSON, no format
	 * fields). Null means "do not filter": an undeclared pack keeps today's
	 * send-to-everyone behavior.
	 */
	public PackFormatRange readRange(Path path) {
		JsonObject pack = readPackObject(path);
		if (pack == null) {
			return null;
		}

		PackFormat minFormat = PackFormat.parse(toPlain(pack.get("min_format")));
		PackFormat maxFormat = PackFormat.parse(toPlain(pack.get("max_format")));
		if (minFormat != null && maxFormat != null) {
			return new PackFormatRange(minFormat, maxFormat, "min_format", false);
		}

		PackFormat packFormat = PackFormat.parse(toPlain(pack.get("pack_format")));
		PackFormatRange supported = legacyRange(pack.get("supported_formats"), packFormat);
		if (supported != null) {
			return clampLegacy(supported);
		}

		if (packFormat != null) {
			return clampLegacy(new PackFormatRange(packFormat, packFormat, "pack_format", false));
		}

		return null;
	}

	private JsonObject readPackObject(Path path) {
		try (ZipFile zip = new ZipFile(path.toFile())) {
			ZipEntry entry = zip.getEntry(MCMETA_ENTRY);
			if (entry == null || entry.getSize() > MAX_MCMETA_BYTES) {
				return null;
			}

			String text;
			try (InputStream input = zip.getInputStream(entry)) {
				text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			}

			JsonElement root = JsonParser.parseString(text);
			if (!root.isJsonObject()) {
				return null;
			}

			JsonElement pack = root.getAsJsonObject().get("pack");
			if (pack == null || !pack.isJsonObject()) {
				return null;
			}

			return pack.getAsJsonObject();
		} catch (Exception exception) {
			logger.warn("Không thể đọc pack.mcmeta trong " + path.getFileName() + ": " + exception.getMessage());
			return null;
		}
	}

	private PackFormatRange legacyRange(JsonElement supported, PackFormat packFormat) {
		if (supported == null) {
			return null;
		}

		if (supported.isJsonPrimitive() || supported.isJsonArray()) {
			PackFormat single = PackFormat.parse(toPlain(supported));
			if (supported.isJsonPrimitive() && single != null) {
				return new PackFormatRange(single, single, "supported_formats", false);
			}

			// a two-element array is a [min, max] pair, not a major.minor format
			if (supported.isJsonArray()) {
				JsonArray array = supported.getAsJsonArray();
				if (array.size() == 2) {
					PackFormat min = PackFormat.parse(toPlain(array.get(0)));
					PackFormat max = PackFormat.parse(toPlain(array.get(1)));
					if (min != null && max != null) {
						return new PackFormatRange(min, max, "supported_formats", false);
					}
				}
			}

			return null;
		}

		if (supported.isJsonObject()) {
			JsonObject object = supported.getAsJsonObject();
			PackFormat min = PackFormat.parse(toPlain(object.get("min_inclusive")));
			PackFormat max = PackFormat.parse(toPlain(object.get("max_inclusive")));

			if (min == null) {
				min = packFormat;
			}
			if (max == null) {
				max = packFormat;
			}
			if (min != null && max != null) {
				return new PackFormatRange(min, max, "supported_formats", false);
			}
		}

		return null;
	}

	private PackFormatRange clampLegacy(PackFormatRange range) {
		if (!range.max().isAfter(PackFormat.LEGACY_CEILING)) {
			return range;
		}

		return new PackFormatRange(range.min(), PackFormat.LEGACY_CEILING, range.source(), true);
	}

	private Object toPlain(JsonElement element) {
		if (element == null) {
			return null;
		}

		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isNumber()) {
				return primitive.getAsNumber();
			}
			if (primitive.isString()) {
				return primitive.getAsString();
			}
			return null;
		}

		if (element.isJsonArray()) {
			List<Object> values = new ArrayList<>();
			for (JsonElement item : element.getAsJsonArray()) {
				values.add(toPlain(item));
			}
			return values;
		}

		return null;
	}
}
