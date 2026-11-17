package dev.belikhun.luna.bpm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the catalog learned about each model file, keyed by its path under models/ and
 * trusted while the file's size and modification time are unchanged. Without it every
 * boot reads and decodes every model (half a gigabyte here) just to list texture and
 * animation names. The hashes clients see are derived from {@link Entry#sha256} and the
 * server key at load, so a new server key never needs the index rebuilt.
 */
final class ModelIndex {
	static final class Entry {
		long size;
		long modified;
		String sha256;
		String defaultTexture;
		List<String> textureIds;
		List<String> extraAnimationIds;
		Map<String, List<String>> classifiedAnimationIds;
	}

	private static final Type TYPE = new TypeToken<Map<String, Entry>>() { }.getType();
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Path file;
	private final Logger logger;
	private final Map<String, Entry> entries = new HashMap<>();

	ModelIndex(Path file, Logger logger) {
		this.file = file;
		this.logger = logger;
	}

	void load() {
		entries.clear();
		if (!Files.isRegularFile(file)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, Entry> read = GSON.fromJson(reader, TYPE);
			if (read != null) {
				entries.putAll(read);
			}
		} catch (Exception e) {
			// a broken index only costs one slow boot
			logger.warn("Model index unreadable, rebuilding it: {}", e.getMessage());
		}
	}

	/** The entry for {@code path} if the file on disk is still the one it describes. */
	Entry lookup(String path, long size, long modified) {
		Entry entry = entries.get(path);
		if (entry == null || entry.size != size || entry.modified != modified || entry.sha256 == null) {
			return null;
		}

		return entry;
	}

	void put(String path, Entry entry) {
		entries.put(path, entry);
	}

	/** Forget files that are gone, then write the index out. */
	void save(Set<String> present) {
		entries.keySet().retainAll(present);
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");

		try {
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(entries, TYPE, writer);
			}

			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			logger.warn("Could not save the model index: {}", e.getMessage());
		}
	}
}
