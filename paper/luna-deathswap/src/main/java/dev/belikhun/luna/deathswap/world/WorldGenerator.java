package dev.belikhun.luna.deathswap.world;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Builds a round's world in a separate, throwaway Paper process, so the live
 * server never blocks on world creation. The idea is LunaManhunt's; what
 * changed:
 *
 * - The child runs this server's own jar with {@code -DbundlerRepoDir} pointed
 *   at the server root, so it reuses the already-patched Paper and its
 *   libraries: no download, no cache juggling, and always the exact version
 *   the world will be loaded on.
 * - Only the overworld is generated (nether and end are off), which is all a
 *   DeathSwap round uses.
 * - The child's console goes to its own log file instead of the server log.
 * - Every child is tracked and killed if the plugin disables mid-run.
 * - The result is laid out as a classic CraftBukkit world folder, which
 *   Paper 26 imports into its dimension storage by itself on load.
 */
public final class WorldGenerator {
	public enum Stage {
		QUEUED("Đang xếp hàng"),
		BOOTING("Đang khởi động máy tạo"),
		GENERATING("Đang tạo địa hình"),
		SAVING("Đang lưu thế giới"),
		PACKING("Đang đóng gói"),
		DONE("Hoàn tất");

		public final String label;

		Stage(String label) {
			this.label = label;
		}
	}

	/** What the sidebar shows while a world is being made. */
	public record Progress(Stage stage, double fraction, long etaSeconds) {
	}

	public static final class GenerationException extends RuntimeException {
		public GenerationException(String message) {
			super(message);
		}

		public GenerationException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public record Options(
		String javaCommand,
		String serverJar,
		int memoryMb,
		int timeoutSeconds
	) {
	}

	private static final double DEFAULT_EXPECTED_SECONDS = 45D;

	private final Logger logger;
	private final Path root;
	private final Path serverRoot;
	private final ExecutorService executor;
	private final Set<Process> running = ConcurrentHashMap.newKeySet();
	private final Path historyFile;
	private volatile Options options;
	private volatile double expectedSeconds;

	public WorldGenerator(Logger logger, Path root, Options options) {
		this.logger = logger;
		this.root = root;
		this.serverRoot = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
		this.options = options;
		this.historyFile = root.resolve("history.yml");
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "LunaDeathSwap-WorldGen");
			thread.setDaemon(true);
			return thread;
		});
		this.expectedSeconds = readHistory();
	}

	public void setOptions(Options options) {
		this.options = options;
	}

	public Path root() {
		return root;
	}

	/** Expected duration of one generation, learnt from the previous ones. */
	public long expectedSeconds() {
		return Math.round(expectedSeconds);
	}

	/**
	 * Generate a world into {@code output} (which must not exist yet). Runs on
	 * the generator thread; one at a time.
	 */
	public CompletableFuture<Void> generate(String levelName, long seed, Path output, Consumer<Progress> listener) {
		return CompletableFuture.runAsync(() -> {
			try {
				run(levelName, seed, output, listener);
			} catch (GenerationException exception) {
				throw exception;
			} catch (Exception exception) {
				throw new GenerationException(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), exception);
			}
		}, executor);
	}

	public void shutdown() {
		for (Process process : running) {
			process.destroyForcibly();
		}
		running.clear();
		executor.shutdownNow();
	}

	/** Work folders left by a crash, removed on start. */
	public void cleanupWorkDirs() {
		Path work = root.resolve("work");
		if (Files.isDirectory(work)) {
			WorldFiles.deleteTree(work);
		}
	}

	private void run(String levelName, long seed, Path output, Consumer<Progress> listener) throws Exception {
		long started = System.nanoTime();
		Options opts = options;
		ProgressClock clock = new ProgressClock(listener, expectedSeconds, started);
		clock.stage(Stage.BOOTING);

		if (Files.exists(output)) {
			throw new GenerationException("output already exists: " + output);
		}

		Path workDir = root.resolve("work").resolve(levelName);
		Path logFile = root.resolve("logs").resolve(levelName + ".log");
		WorldFiles.deleteTree(workDir);
		Files.createDirectories(workDir);
		Files.createDirectories(logFile.getParent());
		pruneLogs(logFile.getParent(), 10);

		Process process = null;
		try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8), true)) {
			writeServerFiles(workDir, levelName, seed);

			List<String> command = new ArrayList<>();
			command.add(resolveJava(opts));
			command.add("-Xms" + Math.min(512, opts.memoryMb()) + "M");
			command.add("-Xmx" + opts.memoryMb() + "M");
			command.add("-DbundlerRepoDir=" + serverRoot);
			command.add("-Dcom.mojang.eula.agree=true");
			command.add("-DIReallyKnowWhatIAmDoingISwear=true");
			command.add("-Dpaper.disableChannelLimit=true");
			command.add("-jar");
			command.add(resolveServerJar(opts).toString());
			command.add("--nogui");
			log.println("# " + String.join(" ", command));

			ProcessBuilder builder = new ProcessBuilder(command)
				.directory(workDir.toFile())
				.redirectErrorStream(true);
			process = builder.start();
			running.add(process);

			CountDownLatch ready = new CountDownLatch(1);
			Process child = process;
			Thread reader = new Thread(() -> pump(child, log, ready, clock), "LunaDeathSwap-WorldGen-log");
			reader.setDaemon(true);
			reader.start();

			if (!ready.await(opts.timeoutSeconds(), TimeUnit.SECONDS)) {
				throw new GenerationException("generator not ready after " + opts.timeoutSeconds() + "s (see " + logFile.getFileName() + ")");
			}
			if (!process.isAlive()) {
				throw new GenerationException("generator exited early with code " + process.exitValue() + " (see " + logFile.getFileName() + ")");
			}

			clock.stage(Stage.SAVING);
			try (BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
				stdin.write("save-all flush");
				stdin.newLine();
				stdin.write("stop");
				stdin.newLine();
				stdin.flush();
			}
			if (!process.waitFor(60, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new GenerationException("generator did not stop within 60s");
			}

			clock.stage(Stage.PACKING);
			pack(workDir.resolve(levelName), output);
		} finally {
			if (process != null) {
				if (process.isAlive()) {
					process.destroyForcibly();
				}
				running.remove(process);
			}
			WorldFiles.deleteTree(workDir);
		}

		double took = (System.nanoTime() - started) / 1_000_000_000D;
		expectedSeconds = expectedSeconds <= 0 ? took : expectedSeconds * 0.6 + took * 0.4;
		writeHistory(expectedSeconds);
		clock.stage(Stage.DONE);
		logger.info("[WorldGen] Generated " + levelName + " in " + String.format("%.1f", took) + "s.");
	}

	private void pump(Process process, PrintWriter log, CountDownLatch ready, ProgressClock clock) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				log.println(line);
				if (line.contains("Preparing level") || line.contains("Preparing start region") || line.contains("Preparing spawn area")) {
					clock.stage(Stage.GENERATING);
				}
				if (line.contains("Done (") && line.contains("For help, type")) {
					ready.countDown();
				}
			}
		} catch (IOException ignored) {
			// the process went away; the waiter reports it
		} finally {
			ready.countDown();
		}
	}

	/**
	 * Turn the child's level into a classic CraftBukkit world folder: level.dat
	 * at the root, the overworld's region/entities/poi/data beside it. Paper 26
	 * migrates exactly this layout into its own storage when the world loads.
	 */
	private void pack(Path level, Path output) throws IOException {
		if (!Files.isRegularFile(level.resolve("level.dat"))) {
			throw new GenerationException("generator left no level.dat in " + level);
		}

		Path overworld = level.resolve("dimensions").resolve("minecraft").resolve("overworld");
		Path staging = output.resolveSibling(output.getFileName() + ".partial");
		WorldFiles.deleteTree(staging);
		Files.createDirectories(staging);

		Files.copy(level.resolve("level.dat"), staging.resolve("level.dat"));
		WorldFiles.mergeInto(level.resolve("data"), staging.resolve("data"));
		if (Files.isDirectory(overworld)) {
			// 26.x layout
			WorldFiles.mergeInto(overworld, staging);
		} else if (Files.isDirectory(level.resolve("region"))) {
			// pre-26 layout: already classic
			for (String part : List.of("region", "entities", "poi")) {
				WorldFiles.mergeInto(level.resolve(part), staging.resolve(part));
			}
		} else {
			throw new GenerationException("generator left no overworld region data in " + level);
		}

		Files.deleteIfExists(staging.resolve("session.lock"));
		WorldFiles.move(staging, output);
	}

	private void writeServerFiles(Path workDir, String levelName, long seed) throws IOException {
		Files.writeString(workDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);

		Properties props = new Properties();
		props.setProperty("level-name", levelName);
		props.setProperty("level-seed", String.valueOf(seed));
		props.setProperty("level-type", "minecraft:normal");
		props.setProperty("generate-structures", "true");
		props.setProperty("allow-nether", "false");
		props.setProperty("online-mode", "false");
		props.setProperty("server-ip", "127.0.0.1");
		props.setProperty("server-port", String.valueOf(freePort()));
		props.setProperty("enable-query", "false");
		props.setProperty("enable-rcon", "false");
		props.setProperty("enable-status", "false");
		props.setProperty("max-players", "1");
		props.setProperty("view-distance", "3");
		props.setProperty("simulation-distance", "3");
		props.setProperty("spawn-protection", "0");
		props.setProperty("sync-chunk-writes", "false");
		props.setProperty("motd", "LunaDeathSwap world generator");
		try (var writer = Files.newBufferedWriter(workDir.resolve("server.properties"), StandardCharsets.UTF_8)) {
			props.store(writer, "LunaDeathSwap world generator");
		}

		YamlConfiguration bukkit = new YamlConfiguration();
		bukkit.set("settings.allow-end", false);
		bukkit.set("settings.update-folder", "update");
		bukkit.save(workDir.resolve("bukkit.yml").toFile());
	}

	private String resolveJava(Options opts) {
		if (opts.javaCommand() != null && !opts.javaCommand().isBlank()) {
			return opts.javaCommand();
		}
		return ProcessHandle.current().info().command().orElse("java");
	}

	/** The jar this server was started from, so the child is the same build. */
	private Path resolveServerJar(Options opts) {
		if (opts.serverJar() != null && !opts.serverJar().isBlank()) {
			return serverRoot.resolve(opts.serverJar()).toAbsolutePath().normalize();
		}

		String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
		for (int index = 0; index < arguments.length - 1; index++) {
			if (arguments[index].equals("-jar")) {
				Path jar = serverRoot.resolve(arguments[index + 1]).toAbsolutePath().normalize();
				if (Files.isRegularFile(jar)) {
					return jar;
				}
			}
		}

		String classPath = System.getProperty("java.class.path", "");
		if (classPath.endsWith(".jar") && !classPath.contains(File.pathSeparator)) {
			Path jar = serverRoot.resolve(classPath).toAbsolutePath().normalize();
			if (Files.isRegularFile(jar)) {
				return jar;
			}
		}

		Path fallback = serverRoot.resolve("server.jar");
		if (Files.isRegularFile(fallback)) {
			return fallback;
		}
		throw new GenerationException("cannot find the server jar; set generator.server-jar");
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException exception) {
			throw new GenerationException("no free port for the generator", exception);
		}
	}

	private void pruneLogs(Path folder, int keep) {
		File[] files = folder.toFile().listFiles((dir, name) -> name.endsWith(".log"));
		if (files == null || files.length < keep) {
			return;
		}
		java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
		for (int index = 0; index <= files.length - keep; index++) {
			files[index].delete();
		}
	}

	private double readHistory() {
		if (!Files.isRegularFile(historyFile)) {
			return DEFAULT_EXPECTED_SECONDS;
		}
		double value = YamlConfiguration.loadConfiguration(historyFile.toFile()).getDouble("expected-seconds", DEFAULT_EXPECTED_SECONDS);
		return value > 0 ? value : DEFAULT_EXPECTED_SECONDS;
	}

	private void writeHistory(double seconds) {
		try {
			Files.createDirectories(root);
			YamlConfiguration yaml = new YamlConfiguration();
			yaml.set("expected-seconds", Math.round(seconds * 10D) / 10D);
			yaml.save(historyFile.toFile());
		} catch (IOException exception) {
			logger.warning("[WorldGen] Could not save history.yml: " + exception.getMessage());
		}
	}

	/**
	 * Progress as a share of the expected total time: the child reports no
	 * honest percentage on 26.x, and a bar that moves steadily and finishes
	 * on time reads better than one stuck at a stage boundary.
	 */
	private static final class ProgressClock {
		private final Consumer<Progress> listener;
		private final double expected;
		private final long started;

		private ProgressClock(Consumer<Progress> listener, double expected, long started) {
			this.listener = listener;
			this.expected = Math.max(5D, expected);
			this.started = started;
		}

		private void stage(Stage stage) {
			double elapsed = (System.nanoTime() - started) / 1_000_000_000D;
			double fraction = stage == Stage.DONE ? 1D : Math.min(0.97D, elapsed / expected);
			long eta = stage == Stage.DONE ? 0L : Math.max(1L, Math.round(expected - elapsed));
			try {
				listener.accept(new Progress(stage, fraction, eta));
			} catch (RuntimeException ignored) {
				// a display problem must not break generation
			}
		}
	}
}
