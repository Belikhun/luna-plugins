package dev.belikhun.luna.patpat;

import com.google.gson.JsonObject;

import java.nio.file.Path;

/**
 * One animation in the library, in the plugin's own format: a folder holding
 * {@code animation.json}, {@code texture.png} and {@code sound_<n>.ogg}.
 *
 * @param id        library id, [a-z0-9_-]
 * @param name      display name
 * @param source    where it came from (a Modrinth project slug, or "local")
 * @param sourceUrl page it came from, if any
 * @param license   the source's licence id, if known
 * @param author    the source's author, if known
 * @param duration  animation length in milliseconds
 * @param frame     PatPat "frame" object (totalFrames, scale, offsets)
 * @param sound     PatPat sound settings without the id (volume, pitch), or null for silent
 * @param soundRef  a sound event the pack did not ship (vanilla or PatPat's own), or null
 * @param textureRef a texture the PatPat mod ships itself (its default hand), used instead of texture.png, or null
 * @param sounds    how many sound_<n>.ogg files the folder holds
 * @param folder    the animation's folder
 */
record Animation(
	String id,
	String name,
	String source,
	String sourceUrl,
	String license,
	String author,
	int duration,
	JsonObject frame,
	JsonObject sound,
	String soundRef,
	String textureRef,
	int sounds,
	Path folder
) {
	Path texture() {
		return folder.resolve("texture.png");
	}

	Path soundFile(int index) {
		return folder.resolve("sound_" + index + ".ogg");
	}

	boolean hasSound() {
		return sounds > 0 || soundRef != null;
	}
}
