package dev.belikhun.luna.patpat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The animations the server has, each in its own folder under {@code animations/}.
 *
 * Importing takes any PatPat animation resource pack (a zip with configs under
 * {@code assets/patpat/}), and turns each animation config in it into the plugin's format: the
 * texture and sound files copied out, and the config reduced to what the animation looks and
 * sounds like. Which entities it plays on is left out: the plugin writes that itself from what
 * players choose.
 */
final class AnimationLibrary {
	/** Textures the PatPat mod itself ships, which packs may point at without including. */
	private static final java.util.Set<String> BUILT_IN_TEXTURES = java.util.Set.of("patpat:textures/default/patpat.png");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path root;
	private final Map<String, Animation> animations = new TreeMap<>();
	/** Configs the last import skipped, with why. */
	private List<String> lastSkipped = List.of();

	List<String> lastSkipped() {
		return lastSkipped;
	}

	AnimationLibrary(Path root) {
		this.root = root;
	}

	Collection<Animation> all() {
		return animations.values();
	}

	Animation get(String id) {
		return id == null ? null : animations.get(id.toLowerCase(Locale.ROOT));
	}

	List<String> ids() {
		return new ArrayList<>(animations.keySet());
	}

	void load() throws IOException {
		animations.clear();
		Files.createDirectories(root);
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path dir : dirs.filter(Files::isDirectory).toList()) {
				Path json = dir.resolve("animation.json");
				if (!Files.isRegularFile(json)) {
					continue;
				}
				JsonObject o = GSON.fromJson(Files.readString(json), JsonObject.class);
				if (!o.has("textureRef") && !Files.isRegularFile(dir.resolve("texture.png"))) {
					continue;
				}
				Animation a = new Animation(
					dir.getFileName().toString(),
					str(o, "name", dir.getFileName().toString()),
					str(o, "source", "local"),
					str(o, "sourceUrl", null),
					str(o, "license", null),
					str(o, "author", null),
					o.has("duration") ? o.get("duration").getAsInt() : 240,
					o.has("frame") ? o.getAsJsonObject("frame") : new JsonObject(),
					o.has("sound") && o.get("sound").isJsonObject() ? o.getAsJsonObject("sound") : null,
					str(o, "soundRef", null),
					str(o, "textureRef", null),
					o.has("sounds") ? o.get("sounds").getAsInt() : 0,
					dir
				);
				animations.put(a.id(), a);
			}
		}
	}

	boolean remove(String id) throws IOException {
		Animation a = get(id);
		if (a == null) {
			return false;
		}
		try (Stream<Path> walk = Files.walk(a.folder())) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
		animations.remove(a.id());
		return true;
	}

	/** Information about where a pack came from, stored with each animation imported from it. */
	record Source(String id, String title, String url, String license, String author) {
	}

	/**
	 * Imports every animation config in a PatPat resource pack zip. Returns the ids added (an
	 * existing animation with the same id is replaced).
	 */
	List<String> importZip(byte[] zip, Source source) throws IOException {
		Map<String, byte[]> files = unzip(zip);
		List<Map.Entry<String, JsonObject>> configs = new ArrayList<>();
		for (Map.Entry<String, byte[]> e : files.entrySet()) {
			String path = e.getKey();
			if (!path.startsWith("assets/") || !(path.endsWith(".json") || path.endsWith(".json5"))) {
				continue;
			}
			if (path.endsWith("/sounds.json") || path.contains("/lang/") || path.contains("/models/") || path.contains("/blockstates/")) {
				continue;
			}
			try {
				JsonElement el = Json5.parse(new String(e.getValue(), StandardCharsets.UTF_8));
				if (el.isJsonObject() && el.getAsJsonObject().has("animation")) {
					configs.add(Map.entry(path, el.getAsJsonObject()));
				}
			} catch (RuntimeException ignored) {
				// not an animation config
			}
		}
		if (configs.isEmpty()) {
			throw new IOException("không thấy cấu hình hoạt ảnh PatPat nào trong gói");
		}

		// one broken config (say, one pointing at PatPat's built-in texture, which the pack does not
		// ship) only skips that config, not the whole pack
		List<String> added = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (Map.Entry<String, JsonObject> c : configs) {
			String base = c.getKey().substring(c.getKey().lastIndexOf('/') + 1).replaceAll("\\.json5?$", "");
			String id = slug(configs.size() == 1 ? source.id() : source.id() + "-" + base);
			String name = configs.size() == 1 && source.title() != null ? source.title() : (source.title() != null ? source.title() + " · " : "") + prettify(base);
			try {
				added.add(importOne(files, c.getValue(), id, name, source));
			} catch (IOException | RuntimeException e) {
				skipped.add(id + " (" + e.getMessage() + ")");
			}
		}
		if (added.isEmpty()) {
			throw new IOException("không nhập được cấu hình nào: " + String.join("; ", skipped));
		}
		lastSkipped = skipped;
		return added;
	}

	private String importOne(Map<String, byte[]> files, JsonObject config, String id, String name, Source source) throws IOException {
		JsonObject anim = config.getAsJsonObject("animation");
		String textureId = anim.get("texture").getAsString();
		byte[] texture = findTexture(files, textureId);
		String textureRef = null;
		if (texture == null) {
			// PatPat's own hand, which the mod ships: keep pointing at it
			if (!BUILT_IN_TEXTURES.contains(textureId)) {
				throw new IOException("không tìm thấy texture " + textureId + " cho " + id);
			}
			textureRef = textureId;
		}

		// the sound: an event defined in the pack's own sounds.json, with its .ogg files, or a
		// reference to a sound the pack does not ship
		List<byte[]> soundFiles = new ArrayList<>();
		JsonObject soundSettings = null;
		String soundRef = null;
		if (anim.has("sound")) {
			JsonElement s = anim.get("sound");
			String soundId;
			if (s.isJsonPrimitive()) {
				soundId = s.getAsString();
				soundSettings = new JsonObject();
			} else {
				JsonObject so = s.getAsJsonObject();
				soundId = so.get("id").getAsString();
				soundSettings = new JsonObject();
				copyNumber(so, "volume", soundSettings, "volume");
				copyNumber(so, "min_pitch", soundSettings, "min_pitch");
				copyNumber(so, "minPitch", soundSettings, "min_pitch");
				copyNumber(so, "max_pitch", soundSettings, "max_pitch");
				copyNumber(so, "maxPitch", soundSettings, "max_pitch");
			}
			soundFiles = findSounds(files, soundId);
			if (soundFiles.isEmpty()) {
				soundRef = soundId;
			}
		}

		Path dir = root.resolve(id);
		if (Files.exists(dir)) {
			Animation old = new Animation(id, "", "", null, null, null, 0, new JsonObject(), null, null, null, 0, dir);
			animations.put(id, old);
			remove(id);
		}
		Files.createDirectories(dir);
		if (texture != null) Files.write(dir.resolve("texture.png"), texture);
		for (int i = 0; i < soundFiles.size(); i++) {
			Files.write(dir.resolve("sound_" + i + ".ogg"), soundFiles.get(i));
		}

		JsonObject out = new JsonObject();
		out.addProperty("name", name);
		out.addProperty("source", source.id());
		if (source.url() != null) out.addProperty("sourceUrl", source.url());
		if (source.license() != null) out.addProperty("license", source.license());
		if (source.author() != null) out.addProperty("author", source.author());
		out.addProperty("duration", anim.has("duration") ? anim.get("duration").getAsInt() : 240);
		out.add("frame", anim.has("frame") ? anim.getAsJsonObject("frame") : defaultFrame());
		if (soundSettings != null) out.add("sound", soundSettings);
		if (soundRef != null) out.addProperty("soundRef", soundRef);
		if (textureRef != null) out.addProperty("textureRef", textureRef);
		out.addProperty("sounds", soundFiles.size());
		Files.writeString(dir.resolve("animation.json"), GSON.toJson(out));
		load();
		return id;
	}

	private static JsonObject defaultFrame() {
		JsonObject f = new JsonObject();
		f.addProperty("totalFrames", 1);
		return f;
	}

	private static byte[] findTexture(Map<String, byte[]> files, String id) {
		String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		for (String candidate : List.of(path, path + ".png", "textures/" + path, "textures/" + path + ".png")) {
			byte[] b = files.get("assets/" + ns + "/" + candidate);
			if (b != null) {
				return b;
			}
		}
		return null;
	}

	/** The .ogg files of a sound event defined in the pack's sounds.json (all of its variants). */
	private static List<byte[]> findSounds(Map<String, byte[]> files, String soundId) {
		String ns = soundId.contains(":") ? soundId.substring(0, soundId.indexOf(':')) : "minecraft";
		String event = soundId.contains(":") ? soundId.substring(soundId.indexOf(':') + 1) : soundId;
		List<byte[]> out = new ArrayList<>();
		byte[] soundsJson = files.get("assets/" + ns + "/sounds.json");
		if (soundsJson == null) {
			return out;
		}
		JsonElement root;
		try {
			root = Json5.parse(new String(soundsJson, StandardCharsets.UTF_8));
		} catch (RuntimeException e) {
			return out;
		}
		if (!root.isJsonObject() || !root.getAsJsonObject().has(event)) {
			return out;
		}
		JsonArray list = root.getAsJsonObject().getAsJsonObject(event).getAsJsonArray("sounds");
		if (list == null) {
			return out;
		}
		for (JsonElement s : list) {
			String name = s.isJsonPrimitive() ? s.getAsString() : s.getAsJsonObject().get("name").getAsString();
			String sns = name.contains(":") ? name.substring(0, name.indexOf(':')) : "minecraft";
			String path = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
			byte[] b = files.get("assets/" + sns + "/sounds/" + path + ".ogg");
			if (b != null) {
				out.add(b);
			}
		}
		return out;
	}

	private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry e;
			while ((e = in.getNextEntry()) != null) {
				if (e.isDirectory()) {
					continue;
				}
				String name = e.getName().replace('\\', '/');
				// packs zipped with a wrapping folder: start paths at "assets/"
				int at = name.indexOf("assets/");
				if (at > 0 && !name.startsWith("assets/")) {
					name = name.substring(at);
				}
				out.put(name, in.readAllBytes());
			}
		}
		return out;
	}

	private static void copyNumber(JsonObject from, String key, JsonObject to, String as) {
		if (from.has(key) && from.get(key).isJsonPrimitive()) {
			to.add(as, from.get(key));
		}
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	static String slug(String s) {
		String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
		return out.isEmpty() ? "animation" : out;
	}

	private static String prettify(String base) {
		String s = base.replaceAll("[_-]+", " ").trim();
		return s.isEmpty() ? base : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
