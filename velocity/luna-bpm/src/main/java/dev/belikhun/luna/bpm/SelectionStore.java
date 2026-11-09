package dev.belikhun.luna.bpm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Each player's chosen model and texture, keyed by UUID, in selections.json.
 * The proxy sees a player on every server, so the choice follows them network-wide
 * (PaperBPM kept it in one backend's player data).
 */
public final class SelectionStore {

	public record Selection(String model, String texture) {
		public static final Selection DEFAULT = new Selection("default", "default");
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path file;
	private final Logger logger;
	private final Executor io;
	private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
	private final AtomicBoolean saveQueued = new AtomicBoolean();

	public SelectionStore(Path file, Logger logger, Executor io) {
		this.file = file;
		this.logger = logger;
		this.io = io;
		load();
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, Selection> raw = GSON.fromJson(reader, new TypeToken<Map<String, Selection>>() { }.getType());
			if (raw != null) {
				raw.forEach((id, selection) -> {
					try {
						if (selection != null && selection.model() != null) {
							selections.put(UUID.fromString(id), selection);
						}
					} catch (IllegalArgumentException ignored) {
						// a hand-edited key that is not a UUID
					}
				});
			}
			logger.info("Loaded {} saved model selection(s)", selections.size());
		} catch (Exception e) {
			logger.warn("Could not read {}: {}", file, e.getMessage());
		}
	}

	public int size() {
		return selections.size();
	}

	public Selection get(UUID player) {
		return selections.getOrDefault(player, Selection.DEFAULT);
	}

	public void set(UUID player, String model, String texture) {
		Selection selection = new Selection(model, texture);
		Selection previous = "default".equals(model) ? selections.remove(player) : selections.put(player, selection);
		if (!selection.equals(previous)) {
			queueSave();
		}
	}

	private void queueSave() {
		if (!saveQueued.compareAndSet(false, true)) {
			return;
		}
		io.execute(() -> {
			saveQueued.set(false);
			save();
		});
	}

	public synchronized void save() {
		Map<String, Selection> snapshot = new TreeMap<>();
		selections.forEach((id, selection) -> snapshot.put(id.toString(), selection));
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(snapshot, writer);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			logger.warn("Could not save {}: {}", file, e.getMessage());
		}
	}
}
