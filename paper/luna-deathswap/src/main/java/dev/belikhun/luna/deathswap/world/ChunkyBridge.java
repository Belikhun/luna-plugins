package dev.belikhun.luna.deathswap.world;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * The only class that talks to Chunky. Chunky's API (org.popcraft.chunky.api.ChunkyAPI)
 * is reached by reflection: its jar is built for Java 25 while luna-plugins
 * compiles for 21, and this way LunaDeathSwap also loads fine without Chunky.
 * Chunky runs one task per world and reports progress and completion through
 * global listeners, which are routed here by world name.
 */
final class ChunkyBridge {
	private record Job(CompletableFuture<Void> done, DoubleConsumer progress) {
	}

	private final Object api;
	private final Method isRunning;
	private final Method startTask;
	private final Method cancelTask;
	private final Map<String, Job> jobs = new ConcurrentHashMap<>();

	private ChunkyBridge(Class<?> apiType, Object api) throws ReflectiveOperationException {
		this.api = api;
		this.isRunning = apiType.getMethod("isRunning", String.class);
		this.startTask = apiType.getMethod("startTask", String.class, String.class,
			double.class, double.class, double.class, double.class, String.class);
		this.cancelTask = apiType.getMethod("cancelTask", String.class);

		// both fire on Chunky's worker threads; the events are records
		Consumer<Object> onProgress = event -> {
			try {
				String world = (String) event.getClass().getMethod("world").invoke(event);
				float percent = (Float) event.getClass().getMethod("progress").invoke(event);
				Job job = jobs.get(world);
				if (job != null) {
					job.progress().accept(Math.max(0D, Math.min(1D, percent / 100D)));
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// a progress tick lost is harmless
			}
		};
		Consumer<Object> onComplete = event -> {
			try {
				String world = (String) event.getClass().getMethod("world").invoke(event);
				Job job = jobs.remove(world);
				if (job != null) {
					job.progress().accept(1D);
					job.done().complete(null);
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// the timeout in SpawnPregen covers a lost completion
			}
		};
		apiType.getMethod("onGenerationProgress", Consumer.class).invoke(api, onProgress);
		apiType.getMethod("onGenerationComplete", Consumer.class).invoke(api, onComplete);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static ChunkyBridge create() throws ReflectiveOperationException {
		var chunky = Bukkit.getPluginManager().getPlugin("Chunky");
		if (chunky == null) {
			return null;
		}
		Class apiType = Class.forName("org.popcraft.chunky.api.ChunkyAPI", true, chunky.getClass().getClassLoader());
		Object api = Bukkit.getServicesManager().load(apiType);
		return api == null ? null : new ChunkyBridge(apiType, api);
	}

	/** Generate a square of the given radius (blocks) around x,z. Completes when Chunky reports the task done. */
	CompletableFuture<Void> generate(World world, double x, double z, double radius, DoubleConsumer progress) {
		String name = world.getName();
		CompletableFuture<Void> done = new CompletableFuture<>();
		Job job = new Job(done, progress);
		try {
			if ((Boolean) isRunning.invoke(api, name) || jobs.putIfAbsent(name, job) != null) {
				done.completeExceptionally(new IllegalStateException("Chunky is already generating " + name));
				return done;
			}
			boolean started = (Boolean) startTask.invoke(api, name, "square", x, z, radius, radius, "concentric");
			if (!started) {
				jobs.remove(name, job);
				done.completeExceptionally(new IllegalStateException("Chunky refused a task for " + name));
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			jobs.remove(name, job);
			done.completeExceptionally(exception);
		}
		return done;
	}

	void cancel(World world) {
		String name = world.getName();
		Job job = jobs.remove(name);
		try {
			if ((Boolean) isRunning.invoke(api, name)) {
				cancelTask.invoke(api, name);
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// nothing more to stop
		}
		if (job != null) {
			job.done().cancel(false);
		}
	}
}
