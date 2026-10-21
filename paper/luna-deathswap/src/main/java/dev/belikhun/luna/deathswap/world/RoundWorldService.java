package dev.belikhun.luna.deathswap.world;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Where round worlds come from and where they go.
 *
 * A small pool of ready worlds is kept on disk (not loaded), filled in the
 * background by {@link WorldGenerator} while players wait, so a round
 * starts the moment its countdown ends. A room that asks for a fixed seed
 * gets that seed generated for it alone. Taking a world moves its folder
 * next to the server and loads it under our namespace; Paper 26 then
 * migrates it into its own dimension storage. Giving a world back unloads
 * it and deletes every place its files can live. Every step is written to
 * {@link WorldRegistry} first, so nothing is ever forgotten.
 *
 * All public methods are called on the main thread.
 */
public final class RoundWorldService {
	public static final String NAMESPACE = "lunadeathswap";
	/** Key paths of round worlds: lunadeathswap:round_<id>. */
	public static final String ROUND_PREFIX = "round_";
	/**
	 * World names, as Paper 26 derives them from a non-minecraft key
	 * ("<namespace>_<path>"); also the folder a pooled world is moved to.
	 */
	public static final String LEVEL_PREFIX = NAMESPACE + "_" + ROUND_PREFIX;
	/** What the first builds called their worlds; still swept up. */
	private static final String LEGACY_PREFIX = "deathswap_";

	public record Settings(boolean useGenerator, int poolSize, int deleteRetries) {
	}

	/** A snapshot for the sidebar. */
	public record Status(boolean busy, String stageLabel, double fraction, long etaSeconds, int ready, int poolSize) {
	}

	/** One generation in flight. */
	private static final class Job {
		final String id;
		final boolean reserved;
		final CompletableFuture<String> done = new CompletableFuture<>();
		volatile WorldGenerator.Stage stage = WorldGenerator.Stage.QUEUED;
		volatile long startedAt;

		Job(String id, boolean reserved) {
			this.id = id;
			this.reserved = reserved;
		}
	}

	private final JavaPlugin plugin;
	private final LunaLogger logger;
	private final WorldRegistry registry;
	private final WorldGenerator generator;
	private final Path poolDir;
	private final Path container;
	private final Map<String, Job> jobs = new LinkedHashMap<>();
	/** Pooled worlds promised to a waiting room; the pool and other rooms leave them alone. */
	private final Set<String> reserved = new LinkedHashSet<>();

	private Settings settings;
	private int failures;
	private boolean refillPending;
	private boolean shuttingDown;

	public RoundWorldService(JavaPlugin plugin, LunaLogger logger, Settings settings, WorldGenerator.Options generatorOptions) {
		this.plugin = plugin;
		this.logger = logger;
		this.settings = settings;
		this.container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
		Path generatorRoot = plugin.getDataFolder().toPath().resolve("generator");
		this.poolDir = generatorRoot.resolve("pool");
		this.registry = new WorldRegistry(new File(plugin.getDataFolder(), "worlds.yml"), plugin.getLogger());
		this.generator = new WorldGenerator(plugin.getLogger(), generatorRoot, generatorOptions);
	}

	public void reload(Settings settings, WorldGenerator.Options generatorOptions) {
		this.settings = settings;
		generator.setOptions(generatorOptions);
		refill();
	}

	// ------------------------------------------------------------------
	// startup and shutdown

	/** Finish whatever the last run left half done, then fill the pool. */
	public void start() {
		registry.load();
		generator.cleanupWorkDirs();

		int removed = 0;
		for (WorldRegistry.Entry entry : registry.all()) {
			switch (entry.state) {
				case POOLED -> {
					if (!Files.isRegularFile(poolDir.resolve(entry.id).resolve("level.dat"))) {
						deleteFiles(entry.id, null);
						registry.remove(entry.id);
						removed++;
					}
				}
				case GENERATING, ACTIVE, DELETING -> {
					if (deleteFiles(entry.id, null)) {
						registry.remove(entry.id);
						removed++;
					} else {
						registry.setState(entry.id, WorldRegistry.State.DELETING);
						registry.noteDeleteAttempt(entry.id);
					}
				}
			}
		}

		removed += sweepOrphans();
		if (removed > 0) {
			logger.info("Cleaned up " + removed + " leftover round world(s).");
		}

		refill();
	}

	public void shutdown() {
		shuttingDown = true;
		generator.shutdown();
		for (Job job : jobs.values()) {
			// the generator is gone; the next start removes the half-made world
			registry.setState(job.id, WorldRegistry.State.GENERATING);
		}
	}

	/** Anything with our prefix that the registry does not account for. */
	private int sweepOrphans() {
		Set<String> known = new LinkedHashSet<>();
		for (WorldRegistry.Entry entry : registry.all()) {
			known.add(entry.id);
		}

		int removed = 0;
		for (Path candidate : listChildren(poolDir)) {
			String name = candidate.getFileName().toString();
			if (name.endsWith(".partial") || !known.contains(name)) {
				removed += WorldFiles.deleteTree(candidate) ? 1 : 0;
			}
		}

		for (Path candidate : listChildren(container)) {
			String name = candidate.getFileName().toString();
			if (Bukkit.getWorld(name) != null) {
				continue;
			}
			if (name.startsWith(LEVEL_PREFIX) && !known.contains(name.substring(LEVEL_PREFIX.length()))) {
				removed += WorldFiles.deleteTree(candidate) ? 1 : 0;
			} else if (name.startsWith(LEGACY_PREFIX) && !name.equals(LEGACY_PREFIX + "lobby")) {
				removed += WorldFiles.deleteTree(candidate) ? 1 : 0;
			}
		}

		for (Path candidate : listChildren(dimensionNamespaceRoot())) {
			String path = candidate.getFileName().toString();
			if (!path.startsWith(ROUND_PREFIX)) {
				continue;
			}
			String id = path.substring(ROUND_PREFIX.length());
			if (!known.contains(id) && Bukkit.getWorld(key(id)) == null) {
				removed += WorldFiles.deleteTree(candidate) ? 1 : 0;
			}
		}
		return removed;
	}

	// ------------------------------------------------------------------
	// generation and the pool

	private int freePooled() {
		int count = 0;
		for (WorldRegistry.Entry entry : registry.withState(WorldRegistry.State.POOLED)) {
			if (!reserved.contains(entry.id)) {
				count++;
			}
		}
		return count;
	}

	private int poolJobsRunning() {
		int count = 0;
		for (Job job : jobs.values()) {
			if (!job.reserved) {
				count++;
			}
		}
		return count;
	}

	public void refill() {
		if (shuttingDown || !settings.useGenerator() || refillPending) {
			return;
		}
		if (freePooled() + poolJobsRunning() < settings.poolSize()) {
			startGeneration(ThreadLocalRandom.current().nextLong(), false);
		}
	}

	private Job startGeneration(long seed, boolean reserve) {
		String id = newId();
		registry.put(id, WorldRegistry.State.GENERATING, seed);
		Job job = new Job(id, reserve);
		jobs.put(id, job);
		logger.audit("Generating round world " + id + " (seed " + seed + (reserve ? ", reserved" : "") + ").");

		generator.generate(levelName(id), seed, poolDir.resolve(id), progress -> {
			if (job.stage == WorldGenerator.Stage.QUEUED) {
				job.startedAt = System.currentTimeMillis();
			}
			job.stage = progress.stage();
		}).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
			jobs.remove(id);
			if (shuttingDown) {
				job.done.completeExceptionally(new IllegalStateException("shutting down"));
				return;
			}

			if (error == null) {
				registry.setState(id, WorldRegistry.State.POOLED);
				if (reserve) {
					reserved.add(id);
				}
				failures = 0;
				job.done.complete(id);
				refill();
				return;
			}

			Throwable cause = error.getCause() != null ? error.getCause() : error;
			logger.warn("World generation failed: " + cause.getMessage());
			deleteFiles(id, null);
			registry.remove(id);
			failures++;
			job.done.completeExceptionally(cause);
			scheduleRefill();
		}));
		return job;
	}

	/** Back off after failures: 30s, 60s, 120s ... up to 10 minutes. */
	private void scheduleRefill() {
		if (refillPending || shuttingDown) {
			return;
		}
		refillPending = true;
		long delaySeconds = Math.min(600L, 30L * (1L << Math.min(5, Math.max(0, failures - 1))));
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			refillPending = false;
			refill();
		}, delaySeconds * 20L);
	}

	// ------------------------------------------------------------------
	// taking and returning worlds

	/**
	 * A loaded world for a new round. Seed 0 takes a pooled world when one is
	 * ready, or waits for a fresh one; any other seed is generated for this
	 * request alone. The in-process generator is the last resort. Completes
	 * on the main thread, with null only when even that failed.
	 */
	public CompletableFuture<World> acquire(long seed) {
		if (seed == 0L) {
			WorldRegistry.Entry pooled = firstFreePooled();
			if (pooled != null) {
				World world = loadPooled(pooled);
				if (world != null) {
					refill();
					return CompletableFuture.completedFuture(world);
				}
			}
		}

		if (!settings.useGenerator()) {
			return CompletableFuture.completedFuture(createInProcess(seed == 0L ? ThreadLocalRandom.current().nextLong() : seed));
		}

		if (seed == 0L) {
			// a pool world already on its way is sooner than a new one queued behind it
			for (Job job : jobs.values()) {
				if (!job.reserved) {
					CompletableFuture<World> result = new CompletableFuture<>();
					job.done.whenComplete((id, error) -> Bukkit.getScheduler().runTask(plugin, () ->
						acquire(0L).whenComplete((world, failure) -> result.complete(world))));
					return result;
				}
			}
		}

		// a dedicated, reserved generation: nobody else can take it on the way
		Job job = startGeneration(seed == 0L ? ThreadLocalRandom.current().nextLong() : seed, true);
		CompletableFuture<World> result = new CompletableFuture<>();
		job.done.whenComplete((id, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
			World world = null;
			if (error == null) {
				reserved.remove(id);
				WorldRegistry.Entry entry = registry.get(id);
				world = entry == null ? null : loadPooled(entry);
			}
			if (world == null) {
				logger.warn("Falling back to in-process world generation.");
				world = createInProcess(seed == 0L ? ThreadLocalRandom.current().nextLong() : seed);
			}
			result.complete(world);
			refill();
		}));
		return result;
	}

	private WorldRegistry.Entry firstFreePooled() {
		for (WorldRegistry.Entry entry : registry.withState(WorldRegistry.State.POOLED)) {
			if (!reserved.contains(entry.id)) {
				return entry;
			}
		}
		return null;
	}

	private World loadPooled(WorldRegistry.Entry entry) {
		Path from = poolDir.resolve(entry.id);
		Path to = container.resolve(levelName(entry.id));
		try {
			registry.setState(entry.id, WorldRegistry.State.ACTIVE);
			WorldFiles.deleteTree(to);
			WorldFiles.move(from, to);
			long started = System.nanoTime();
			World world = creator(entry.id, entry.seed).createWorld();
			if (world == null) {
				throw new IllegalStateException("createWorld returned null");
			}
			logger.info("Loaded round world " + entry.id + " in " + (System.nanoTime() - started) / 1_000_000L + "ms (folder " + world.getWorldFolder() + ").");
			return world;
		} catch (Throwable throwable) {
			logger.error("Could not load round world " + entry.id + ".", throwable);
			World loaded = Bukkit.getWorld(key(entry.id));
			if (loaded != null) {
				Bukkit.unloadWorld(loaded, false);
			}
			if (deleteFiles(entry.id, null)) {
				registry.remove(entry.id);
			} else {
				registry.setState(entry.id, WorldRegistry.State.DELETING);
			}
			return null;
		}
	}

	/** Blocks the main thread for a moment; only used when the generator cannot help. */
	private World createInProcess(long seed) {
		String id = newId();
		registry.put(id, WorldRegistry.State.ACTIVE, seed);
		try {
			World world = creator(id, seed).createWorld();
			if (world == null) {
				throw new IllegalStateException("createWorld returned null");
			}
			logger.info("Created round world " + id + " in-process.");
			return world;
		} catch (Throwable throwable) {
			logger.error("Could not create a round world.", throwable);
			deleteFiles(id, null);
			registry.remove(id);
			return null;
		}
	}

	private WorldCreator creator(String id, long seed) {
		// Paper 26 takes a key or a name, never both; the name follows from the key
		return WorldCreator.ofKey(key(id))
			.seed(seed)
			.environment(World.Environment.NORMAL);
	}

	/**
	 * Unload a round world and delete it. Players must already be out of it.
	 * Deletion runs off the main thread and is retried; whatever is left is
	 * finished on the next start.
	 */
	public void release(World world) {
		if (world == null) {
			return;
		}
		String id = idOf(world);
		if (id == null) {
			logger.warn("Refusing to release " + world.getName() + ": not a round world.");
			return;
		}

		registry.setState(id, WorldRegistry.State.DELETING);
		Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
		if (!world.getPlayers().isEmpty()) {
			logger.warn("Players still in " + world.getName() + " while releasing it.");
		}
		if (!Bukkit.unloadWorld(world, false)) {
			logger.warn("Could not unload " + world.getName() + "; it is removed on the next start.");
			return;
		}
		if (shuttingDown || !plugin.isEnabled()) {
			// no scheduler while disabling: delete now, or leave it for the next start
			if (deleteFiles(id, folder)) {
				registry.remove(id);
			}
			return;
		}
		deleteLater(id, folder, 0);
	}

	private void deleteLater(String id, Path folder, int attempt) {
		Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
			boolean done = deleteFiles(id, folder);
			if (!plugin.isEnabled()) {
				return;
			}
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (done) {
					registry.remove(id);
					logger.info("Deleted round world " + id + ".");
				} else if (attempt + 1 < settings.deleteRetries()) {
					registry.noteDeleteAttempt(id);
					deleteLater(id, folder, attempt + 1);
				} else {
					logger.warn("Could not fully delete round world " + id + "; retrying on the next start.");
				}
			});
		}, attempt == 0 ? 20L : 100L * attempt);
	}

	/** Every place a round world's files can be, by id. Never anything outside them. */
	private boolean deleteFiles(String id, Path knownFolder) {
		List<Path> places = new ArrayList<>();
		places.add(poolDir.resolve(id));
		places.add(poolDir.resolve(id + ".partial"));
		places.add(container.resolve(levelName(id)));
		places.add(dimensionNamespaceRoot().resolve(ROUND_PREFIX + id));
		if (knownFolder != null && knownFolder.getFileName() != null
			&& knownFolder.getFileName().toString().contains(id)
			&& !knownFolder.equals(container)) {
			places.add(knownFolder);
		}

		boolean clean = true;
		for (Path place : places) {
			clean &= WorldFiles.deleteTree(place);
		}
		return clean;
	}

	// ------------------------------------------------------------------
	// naming and status

	public static String levelName(String id) {
		return LEVEL_PREFIX + id;
	}

	public static NamespacedKey key(String id) {
		return new NamespacedKey(NAMESPACE, ROUND_PREFIX + id);
	}

	/** The round id of one of our worlds, or null (the lobby is not a round world). */
	public static String idOf(World world) {
		if (world == null) {
			return null;
		}
		NamespacedKey key = world.getKey();
		if (key != null && NAMESPACE.equals(key.getNamespace())) {
			String path = key.getKey();
			return path.startsWith(ROUND_PREFIX) ? path.substring(ROUND_PREFIX.length()) : null;
		}
		String name = world.getName();
		return name.startsWith(LEVEL_PREFIX) ? name.substring(LEVEL_PREFIX.length()) : null;
	}

	public static boolean isRoundWorld(World world) {
		return idOf(world) != null;
	}

	Path dimensionNamespaceRoot() {
		String mainLevel = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
		return container.resolve(mainLevel).resolve("dimensions").resolve(NAMESPACE);
	}

	public Status status() {
		int ready = freePooled();
		Job running = null;
		for (Job job : jobs.values()) {
			if (job.stage != WorldGenerator.Stage.QUEUED) {
				running = job;
				break;
			}
		}
		if (running == null && !jobs.isEmpty()) {
			return new Status(true, WorldGenerator.Stage.QUEUED.label, 0D, generator.expectedSeconds() * jobs.size(), ready, settings.poolSize());
		}
		if (running == null) {
			return new Status(false, "", ready >= settings.poolSize() ? 1D : 0D, 0L, ready, settings.poolSize());
		}
		double expected = Math.max(5D, generator.expectedSeconds());
		double elapsed = (System.currentTimeMillis() - running.startedAt) / 1000D;
		double fraction = Math.min(0.97D, elapsed / expected);
		long eta = Math.max(1L, Math.round(expected - elapsed));
		return new Status(true, running.stage.label, fraction, eta, ready, settings.poolSize());
	}

	public List<String> describe() {
		List<String> lines = new ArrayList<>();
		for (WorldRegistry.Entry entry : registry.all()) {
			lines.add(entry.id + " " + entry.state.name().toLowerCase(Locale.ROOT)
				+ (reserved.contains(entry.id) ? " (đã giữ chỗ)" : "")
				+ (entry.deleteAttempts > 0 ? " (xóa thử " + entry.deleteAttempts + " lần)" : ""));
		}
		return lines;
	}

	public long expectedGenerationSeconds() {
		return generator.expectedSeconds();
	}

	/** Run the startup cleanup again by hand; returns how much it removed. */
	public int cleanupNow() {
		int removed = 0;
		for (WorldRegistry.Entry entry : registry.withState(WorldRegistry.State.DELETING)) {
			if (Bukkit.getWorld(key(entry.id)) == null && deleteFiles(entry.id, null)) {
				registry.remove(entry.id);
				removed++;
			}
		}
		return removed + sweepOrphans();
	}

	/** Start one pool generation now, even when the pool is full. */
	public boolean generateNow() {
		if (!settings.useGenerator() || shuttingDown) {
			return false;
		}
		startGeneration(ThreadLocalRandom.current().nextLong(), false);
		return true;
	}

	private static List<Path> listChildren(Path folder) {
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> children = Files.list(folder)) {
			return children.filter(Files::isDirectory).toList();
		} catch (Exception exception) {
			return List.of();
		}
	}

	private static String newId() {
		String letters = "abcdefghijklmnopqrstuvwxyz";
		StringBuilder out = new StringBuilder();
		for (int index = 0; index < 7; index++) {
			out.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
		}
		return out.toString();
	}
}
