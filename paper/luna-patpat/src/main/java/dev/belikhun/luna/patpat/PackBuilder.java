package dev.belikhun.luna.patpat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the resource pack: every animation in the library (textures and sounds, under
 * {@code patpat:luna/<id>}), and one PatPat animation config per group of choices that use the same
 * animation the same way. Writes it where the network's pack loader reads packs from, with the pack
 * loader entry beside it, then runs the configured reload and resend commands.
 */
final class PackBuilder {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String PLAYER = "minecraft:player";

	private final ConfigurationSection config;
	private final Logger log;

	PackBuilder(ConfigurationSection config, Logger log) {
		this.config = config;
		this.log = log;
	}

	record Result(Path zip, int animations, int rules, long bytes) {
	}

	Result build(AnimationLibrary library, Selections selections) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int rules = 0;
		try (ZipOutputStream zip = new ZipOutputStream(buf)) {
			JsonObject meta = new JsonObject();
			JsonObject pack = new JsonObject();
			pack.addProperty("description", config.getString("pack.title", "PatPat"));
			JsonArray min = new JsonArray();
			min.add(config.getInt("pack.min-format", 75));
			min.add(0);
			JsonArray max = new JsonArray();
			max.add(config.getInt("pack.max-format", 99));
			max.add(0);
			pack.add("min_format", min);
			pack.add("max_format", max);
			meta.add("pack", pack);
			put(zip, "pack.mcmeta", GSON.toJson(meta));

			// the assets of every animation in the library
			JsonObject sounds = new JsonObject();
			StringBuilder credits = new StringBuilder("PatPat animations bundled by LunaPatPat\n\n");
			for (Animation a : library.all()) {
				if (a.textureRef() == null) {
					put(zip, "assets/patpat/textures/luna/" + a.id() + ".png", Files.readAllBytes(a.texture()));
				}
				if (a.sounds() > 0) {
					JsonArray list = new JsonArray();
					for (int i = 0; i < a.sounds(); i++) {
						put(zip, "assets/patpat/sounds/luna/" + a.id() + "/" + i + ".ogg", Files.readAllBytes(a.soundFile(i)));
						list.add("patpat:luna/" + a.id() + "/" + i);
					}
					JsonObject event = new JsonObject();
					event.add("sounds", list);
					sounds.add("luna." + a.id(), event);
				}
				credits.append(a.id()).append(": ").append(a.name())
					.append(" | source ").append(a.source())
					.append(a.sourceUrl() != null ? " (" + a.sourceUrl() + ")" : "")
					.append(a.author() != null ? " | by " + a.author() : "")
					.append(a.license() != null ? " | licence " + a.license() : "")
					.append('\n');
			}
			put(zip, "assets/patpat/sounds.json", GSON.toJson(sounds));
			put(zip, "assets/patpat/luna/CREDITS.txt", credits.toString());

			// the rules: one config per animation and kind of choice
			Map<String, List<JsonObject>> incoming = new LinkedHashMap<>();
			Map<String, List<JsonObject>> outgoing = new LinkedHashMap<>();
			Map<String, List<JsonObject>> pairs = new LinkedHashMap<>();
			for (Selections.PlayerChoice c : selections.players()) {
				if (library.get(c.incoming) != null) incoming.computeIfAbsent(c.incoming, k -> new ArrayList<>()).add(patted(c.uuid, null));
				if (library.get(c.outgoing) != null) outgoing.computeIfAbsent(c.outgoing, k -> new ArrayList<>()).add(patted(null, c.uuid));
			}
			for (Selections.Pair p : selections.pairs()) {
				if (library.get(p.animation()) != null) pairs.computeIfAbsent(p.animation(), k -> new ArrayList<>()).add(patted(p.to(), p.from()));
			}
			rules += writeRules(zip, library, "pair", config.getInt("priority.pair", 3000), pairs);
			rules += writeRules(zip, library, "incoming", config.getInt("priority.incoming", 2000), incoming);
			rules += writeRules(zip, library, "outgoing", config.getInt("priority.outgoing", 1000), outgoing);
			Animation def = library.get(selections.defaultAnimation());
			if (def != null) {
				JsonObject rule = rule(def, config.getInt("priority.default", 10));
				rule.addProperty("entities", "all");
				put(zip, "assets/patpat/textures/luna/rules/default.json", GSON.toJson(rule));
				rules++;
			}
		}

		Path dir = Path.of(config.getString("pack.directory", "packs"));
		String name = config.getString("pack.name", "lunapatpat");
		Files.createDirectories(dir);
		Path zipPath = dir.resolve(name + ".zip");
		Path tmp = dir.resolve(name + ".zip.tmp");
		Files.write(tmp, buf.toByteArray());
		Files.move(tmp, zipPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		writeEntry(dir.resolve(name + ".yml"), name);
		return new Result(zipPath, library.all().size(), rules, buf.size());
	}

	/** The entity entry of a rule: a player who is patted (or any), patted by someone (or anyone). */
	private static JsonObject patted(UUID target, UUID by) {
		JsonObject e = new JsonObject();
		e.addProperty("id", PLAYER);
		if (target != null) e.addProperty("uuid", target.toString());
		if (by != null) {
			JsonArray from = new JsonArray();
			JsonObject who = new JsonObject();
			who.addProperty("uuid", by.toString());
			from.add(who);
			e.add("from", from);
		}
		return e;
	}

	private int writeRules(ZipOutputStream zip, AnimationLibrary library, String kind, int priority, Map<String, List<JsonObject>> groups) throws IOException {
		int n = 0;
		for (Map.Entry<String, List<JsonObject>> g : groups.entrySet()) {
			JsonObject rule = rule(library.get(g.getKey()), priority);
			JsonArray entities = new JsonArray();
			g.getValue().forEach(entities::add);
			rule.add("entities", entities);
			put(zip, "assets/patpat/textures/luna/rules/" + kind + "-" + g.getKey() + ".json", GSON.toJson(rule));
			n++;
		}
		return n;
	}

	private static JsonObject rule(Animation a, int priority) {
		JsonObject rule = new JsonObject();
		rule.addProperty("version", "1.0.1");
		rule.addProperty("priority", priority);
		JsonObject anim = new JsonObject();
		anim.addProperty("texture", a.textureRef() != null ? a.textureRef() : "patpat:textures/luna/" + a.id() + ".png");
		anim.addProperty("duration", a.duration());
		anim.add("frame", a.frame().deepCopy());
		if (a.hasSound()) {
			JsonObject sound = a.sound() != null ? a.sound().deepCopy() : new JsonObject();
			sound.addProperty("id", a.sounds() > 0 ? "patpat:luna." + a.id() : a.soundRef());
			anim.add("sound", sound);
		}
		rule.add("animation", anim);
		rule.addProperty("blacklist", false);
		return rule;
	}

	private void writeEntry(Path yml, String name) throws IOException {
		StringBuilder s = new StringBuilder();
		// the pack loader addresses packs by this name (forceload, resend), so it is the id, not the title
		s.append("name: \"").append(name).append("\"\n");
		s.append("filename: \"").append(name).append(".zip\"\n");
		s.append("priority: ").append(config.getInt("pack.priority", 6)).append('\n');
		s.append("required: ").append(config.getBoolean("pack.required", false)).append('\n');
		s.append("enabled: true\n");
		s.append("servers:\n");
		for (String server : config.getStringList("pack.servers")) {
			s.append("  - \"").append(server).append("\"\n");
		}
		String text = s.toString();
		if (!Files.exists(yml) || !Files.readString(yml).equals(text)) {
			Files.writeString(yml, text);
		}
	}

	/** Runs the configured after-build commands; returns their combined output, for the log. */
	String afterBuild(List<String> online) {
		StringBuilder out = new StringBuilder();
		for (String command : config.getStringList("after-build.reload")) {
			out.append(run(command));
		}
		String pack = config.getString("pack.name", "lunapatpat");
		for (String player : online) {
			if (!player.matches("[A-Za-z0-9_.]{1,16}")) continue;
			for (String command : config.getStringList("after-build.per-player")) {
				out.append(run(command.replace("{player}", player).replace("{pack}", pack)));
			}
		}
		return out.toString();
	}

	private String run(String command) {
		try {
			Process p = new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
			boolean done = p.waitFor(90, TimeUnit.SECONDS);
			String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (!done) {
				p.destroyForcibly();
				return "[" + command + "] timed out\n";
			}
			return "[" + command + "] exit " + p.exitValue() + (text.isEmpty() ? "" : ": " + lastLine(text)) + "\n";
		} catch (IOException e) {
			log.warning("could not run '" + command + "': " + e.getMessage());
			return "[" + command + "] failed: " + e.getMessage() + "\n";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "[" + command + "] interrupted\n";
		}
	}

	private static String lastLine(String s) {
		String[] lines = s.split("\n");
		return lines[lines.length - 1].replaceAll("\u001B\\[[;\\d]*m", "");
	}

	private static void put(ZipOutputStream zip, String path, String text) throws IOException {
		put(zip, path, text.getBytes(StandardCharsets.UTF_8));
	}

	private static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
		ZipEntry e = new ZipEntry(path);
		e.setTime(0);
		zip.putNextEntry(e);
		zip.write(bytes);
		zip.closeEntry();
	}
}
