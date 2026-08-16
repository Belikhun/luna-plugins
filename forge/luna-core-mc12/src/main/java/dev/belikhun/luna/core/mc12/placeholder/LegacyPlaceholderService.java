package dev.belikhun.luna.core.mc12.placeholder;

import dev.belikhun.luna.legacy.heartbeat.BackendIdentity;
import dev.belikhun.luna.legacy.heartbeat.BackendMetadata;
import dev.belikhun.luna.legacy.heartbeat.BackendServerProbe;
import dev.belikhun.luna.legacy.logging.LunaLogger;
import dev.belikhun.luna.legacy.placeholder.LunaPlaceholderExtension;
import dev.belikhun.luna.legacy.placeholder.PlaceholderEscaping;
import dev.belikhun.luna.legacy.placeholder.PlaceholderProvider;
import dev.belikhun.luna.legacy.placeholder.PlaceholderRoute;
import dev.belikhun.luna.legacy.placeholder.PlaceholderRouting;
import dev.belikhun.luna.legacy.placeholder.PlaceholderService;
import dev.belikhun.luna.legacy.placeholder.PlaceholderSnapshot;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.ui.LunaProgressBar;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Placeholders on 1.12.2.
 *
 * The routing, the escaping and the snapshot shape are the shared ones, so an
 * identifier written for any other backend resolves the same way here. What this
 * class adds is the half only a platform can answer: how many entities a world is
 * carrying, what a player's ping is, and where the numbers behind `%luna_tps%`
 * come from on a server with no TPS accessor.
 *
 * **The shared statistics are sampled on a timer, not per lookup.** A tab list
 * asks for thirty placeholders per player per refresh; counting entities thirty
 * times over would cost more than the tab list is worth. {@link #refreshSharedSnapshot}
 * takes one sample and every lookup until the next one reads it.
 *
 * Two things the modern service has are deliberately absent. There is no spark
 * bridge, because spark's 1.12.2 build exposes none of the API the modern one
 * reflects against, and `%luna_spark_*%` therefore falls back to the tick times
 * luna measures itself. There is no reflective member cache either: that exists
 * to reach fields that moved between modern versions, and this line has exactly
 * one version.
 */
public final class LegacyPlaceholderService implements PlaceholderService<EntityPlayerMP> {
	static final int DEFAULT_BAR_WIDTH = 25;
	static final int MIN_BAR_WIDTH = 1;
	static final int MAX_BAR_WIDTH = 120;
	static final String DEFAULT_COLOR = "#F1FF68";
	static final String LUNA_PREFIX = "luna_";

	/** What a tick costs when the server is keeping up; the divisor for TPS maths. */
	private static final double NANOS_PER_TICK = 50_000_000D;

	private final LunaLogger logger;
	private final MinecraftServer server;
	private final BackendServerProbe probe;
	private final BackendIdentity identity;
	private final List<LegacyPlaceholderProvider> providers;
	private final Map<String, List<LegacyPlaceholderProvider>> providersByNamespace;
	private final Map<String, LunaPlaceholderExtension<EntityPlayerMP>> extensionsByNamespace;
	private final CopyOnWriteArrayList<LunaPlaceholderExtension<EntityPlayerMP>> extensions;
	private final long startedAtMillis;

	private volatile SharedSnapshot latestSharedSnapshot;

	public LegacyPlaceholderService(
		LunaLogger logger,
		MinecraftServer server,
		BackendServerProbe probe,
		BackendIdentity identity,
		List<LegacyPlaceholderProvider> providers
	) {
		this.logger = logger.scope("Placeholders");
		this.server = server;
		this.probe = probe;
		this.identity = identity;
		this.providers = Collections.unmodifiableList(new ArrayList<LegacyPlaceholderProvider>(providers));
		this.providersByNamespace = PlaceholderRouting.indexProvidersByNamespace(this.providers);
		this.extensionsByNamespace = new ConcurrentHashMap<String, LunaPlaceholderExtension<EntityPlayerMP>>();
		this.extensions = new CopyOnWriteArrayList<LunaPlaceholderExtension<EntityPlayerMP>>();
		this.startedAtMillis = System.currentTimeMillis();
		this.latestSharedSnapshot = sample();
	}

	@Override
	public void refreshSharedSnapshot() {
		latestSharedSnapshot = sample();
	}

	@Override
	public Map<String, String> snapshot(EntityPlayerMP player, Collection<String> requestedIdentifiers) {
		if (player == null) {
			return Collections.emptyMap();
		}

		PlaceholderSnapshot snapshot = snapshotFor(player);
		Map<String, String> values = new LinkedHashMap<String, String>();

		for (LegacyPlaceholderProvider provider : providers) {
			provider.contributeSnapshot(this, player, snapshot, values);
		}

		if (requestedIdentifiers != null) {
			for (String identifier : requestedIdentifiers) {
				if (Strings.isBlank(identifier) || values.containsKey(identifier)) {
					continue;
				}

				String resolved = resolveWith(player, identifier, snapshot);

				if (resolved != null) {
					values.put(identifier, resolved);
				}
			}
		}

		return values;
	}

	/**
	 * An extension owns every namespace it claims, and claiming one takes it over.
	 *
	 * Last registration wins, exactly as the modern service behaves, so a module
	 * reloading itself replaces its own entry instead of stacking a second one that
	 * never gets asked.
	 */
	@Override
	public void registerExtension(LunaPlaceholderExtension<EntityPlayerMP> extension) {
		if (extension == null || extension.namespaces() == null) {
			return;
		}

		List<String> claimed = new ArrayList<String>();

		for (String namespace : extension.namespaces()) {
			if (Strings.isBlank(namespace)) {
				continue;
			}

			String normalized = namespace.trim().toLowerCase(Locale.ROOT);
			LunaPlaceholderExtension<EntityPlayerMP> previous = extensionsByNamespace.put(normalized, extension);

			if (previous != null && previous != extension && !extensionsByNamespace.containsValue(previous)) {
				extensions.remove(previous);
			}

			claimed.add(normalized);
		}

		if (claimed.isEmpty()) {
			return;
		}

		extensions.addIfAbsent(extension);
		logger.audit("Đã đăng ký namespace placeholder: " + String.join(", ", claimed));
	}

	@Override
	public String resolvePlaceholder(EntityPlayerMP player, String identifier) {
		if (player == null || Strings.isBlank(identifier)) {
			return null;
		}

		return resolveWith(player, identifier, snapshotFor(player));
	}

	/**
	 * Extensions first, then the core's own providers.
	 *
	 * That order is what lets a module take over an identifier the core would
	 * otherwise answer, which is the whole point of an extension.
	 */
	private String resolveWith(EntityPlayerMP player, String identifier, PlaceholderSnapshot snapshot) {
		String fromExtension = resolveFromExtension(player, identifier);

		if (fromExtension != null) {
			return fromExtension;
		}

		PlaceholderRoute<LegacyPlaceholderProvider> route = PlaceholderRouting.resolve(identifier, providersByNamespace);

		if (route == null) {
			return null;
		}

		for (LegacyPlaceholderProvider provider : route.providers()) {
			String value = provider.resolve(
				this,
				player,
				route.rawNamespace(),
				route.normalizedNamespace(),
				route.rawParams(),
				route.normalizedParams(),
				snapshot
			);

			if (value != null) {
				return route.safeVariant() ? PlaceholderEscaping.escapePercents(value) : value;
			}
		}

		return null;
	}

	private String resolveFromExtension(EntityPlayerMP player, String identifier) {
		if (extensions.isEmpty()) {
			return null;
		}

		String unwrapped = PlaceholderRouting.unwrapIdentifier(identifier);
		int split = unwrapped.indexOf('_');

		if (split <= 0 || split == unwrapped.length() - 1) {
			return null;
		}

		String namespace = unwrapped.substring(0, split).toLowerCase(Locale.ROOT);
		LunaPlaceholderExtension<EntityPlayerMP> extension = extensionsByNamespace.get(namespace);

		if (extension == null) {
			return null;
		}

		try {
			return extension.resolve(player, namespace, unwrapped.substring(split + 1).toLowerCase(Locale.ROOT));
		} catch (Exception failure) {
			logger.warn("Extension placeholder " + namespace + " ném lỗi: " + failure);

			return null;
		}
	}

	// ------------------------------------------------------------- the sampling

	/**
	 * The per-player view of one resolution round.
	 *
	 * Everything shared comes from the last timed sample; only the ping is read
	 * here, because it is the one value that is per player and free to read.
	 */
	private PlaceholderSnapshot snapshotFor(EntityPlayerMP player) {
		SharedSnapshot shared = latestSharedSnapshot;

		return new PlaceholderSnapshot(
			shared.tps,
			shared.tickDurationMillis,
			"",
			pingOf(player),
			shared.uptimeMillis,
			shared.systemCpuPercent,
			shared.processCpuPercent,
			shared.ramUsedBytes,
			shared.ramMaxBytes,
			shared.totalEntities,
			shared.totalLivingEntities,
			shared.totalChunks
		);
	}

	private SharedSnapshot sample() {
		double tps = probe == null ? 20D : probe.tps();
		MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
		long used = memory.getHeapMemoryUsage().getUsed();
		long max = memory.getHeapMemoryUsage().getMax();

		int entities = 0;
		int living = 0;
		int chunks = 0;

		WorldServer[] worlds = server.worlds;

		if (worlds != null) {
			for (WorldServer world : worlds) {
				if (world == null) {
					continue;
				}

				entities += world.loadedEntityList.size();
				living += world.getEntities(net.minecraft.entity.EntityLivingBase.class, entity -> true).size();
				chunks += world.getChunkProvider().getLoadedChunkCount();
			}
		}

		return new SharedSnapshot(
			tps,
			tickDurationMillis(tps),
			System.currentTimeMillis() - startedAtMillis,
			systemCpuPercent(),
			processCpuPercent(),
			used,
			max,
			entities,
			living,
			chunks
		);
	}

	/**
	 * How long a tick is really taking, derived from TPS.
	 *
	 * 1.12.2 keeps `tickTimeArray` but exposes no average, and the probe already
	 * turns that ring buffer into a TPS figure - so the duration is derived back
	 * from it rather than reading the same buffer a second way and risking two
	 * numbers that disagree.
	 */
	private double tickDurationMillis(double tps) {
		if (tps <= 0D) {
			return 0D;
		}

		return Math.max(0D, (NANOS_PER_TICK / 1_000_000D) * (20D / Math.min(20D, tps)));
	}

	/**
	 * The JVM's own CPU share, when the JVM will say.
	 *
	 * `com.sun.management.OperatingSystemMXBean` is not on every JVM's public API,
	 * so it is reached reflectively and a JVM without it reports zero rather than
	 * refusing to start.
	 */
	private double processCpuPercent() {
		return operatingSystemValue("getProcessCpuLoad");
	}

	private double systemCpuPercent() {
		double load = operatingSystemValue("getSystemCpuLoad");

		if (load > 0D) {
			return load;
		}

		// the fallback is the load average, which is per core rather than a share
		double average = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
		int processors = Math.max(1, ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors());

		return average < 0D ? 0D : Math.min(100D, (average / processors) * 100D);
	}

	private double operatingSystemValue(String methodName) {
		try {
			Object bean = ManagementFactory.getOperatingSystemMXBean();
			Method method = bean.getClass().getMethod(methodName);

			method.setAccessible(true);

			Object value = method.invoke(bean);

			if (!(value instanceof Double)) {
				return 0D;
			}

			double fraction = ((Double) value).doubleValue();

			return fraction < 0D ? 0D : Math.min(100D, fraction * 100D);
		} catch (Exception unavailable) {
			return 0D;
		}
	}

	private int pingOf(EntityPlayerMP player) {
		return Math.max(0, player.ping);
	}

	// -------------------------------------------------- what providers call back

	public MinecraftServer server() {
		return server;
	}

	public BackendServerProbe probe() {
		return probe;
	}

	/** Publish under both the bare key and its `luna_` alias, as the fleet does. */
	public void putCore(Map<String, String> values, String key, String value) {
		String normalized = safe(value);

		values.put(key, normalized);
		values.put(LUNA_PREFIX + key, normalized);
	}

	public void putLunaAlias(Map<String, String> values, String key, String value) {
		values.put(LUNA_PREFIX + key, safe(value));
	}

	public String buildBar(LunaProgressBar bar, int width) {
		return bar.width(clampWidth(width)).render();
	}

	public String buildBarOnly(LunaProgressBar bar, int width) {
		return bar.width(clampWidth(width)).renderBar();
	}

	public String buildValueOnly(LunaProgressBar bar) {
		return bar.renderValue();
	}

	public int clampWidth(int width) {
		return Math.max(MIN_BAR_WIDTH, Math.min(MAX_BAR_WIDTH, width));
	}

	public String formatPercent(double value) {
		return String.format(Locale.US, "%.1f%%", Double.valueOf(Math.max(0D, value)));
	}

	public String formatTps(double value) {
		return String.format(Locale.US, "%.2f", Double.valueOf(Math.max(0D, value)));
	}

	/**
	 * What this server is called on the network.
	 *
	 * The proxy names it, and the identity carries that name with the configured
	 * one as its fallback. It is emphatically **not** the server mod name: that is
	 * "forge" on every 1.12.2 backend in the fleet, which is a plausible-looking
	 * answer and the reason this was wrong for as long as it was.
	 */
	public String localServerName() {
		return identity == null ? "" : safe(identity.name());
	}

	/**
	 * The machine this backend runs on, as the proxy knows it.
	 *
	 * Despite the field's name, {@link BackendMetadata#serverName()} carries the
	 * **host**: the proxy fills it from the connection's host when it answers a
	 * heartbeat, so it reads `maylocnuoc` or `mayphatdien`, not `lobby`. That is
	 * what `%luna_host_name%` is for, and wiring it to {@link #localServerName()}
	 * is why this trunk rendered `skyfactory4/skyfactory4` where the modern ones
	 * render `mayphatdien/skyfactory4`.
	 *
	 * Falls back to the server's own name, so a backend the proxy has not answered
	 * yet shows something rather than nothing.
	 */
	public String currentServerInfoName() {
		BackendMetadata metadata = identity == null ? null : identity.current();

		if (metadata != null) {
			String host = metadata.sanitize().serverName();

			if (!Strings.isBlank(host)) {
				return safe(host);
			}
		}

		return localServerName();
	}

	public String safe(String value) {
		return value == null ? "" : value;
	}

	/** What a provider is: the service type is fixed, so callers say it once. */
	public interface LegacyPlaceholderProvider extends PlaceholderProvider<LegacyPlaceholderService, EntityPlayerMP> {
	}

	/** The statistics one timed sample sees, shared by every player until the next. */
	private static final class SharedSnapshot {
		private final double tps;
		private final double tickDurationMillis;
		private final long uptimeMillis;
		private final double systemCpuPercent;
		private final double processCpuPercent;
		private final long ramUsedBytes;
		private final long ramMaxBytes;
		private final int totalEntities;
		private final int totalLivingEntities;
		private final int totalChunks;

		private SharedSnapshot(
			double tps,
			double tickDurationMillis,
			long uptimeMillis,
			double systemCpuPercent,
			double processCpuPercent,
			long ramUsedBytes,
			long ramMaxBytes,
			int totalEntities,
			int totalLivingEntities,
			int totalChunks
		) {
			this.tps = tps;
			this.tickDurationMillis = tickDurationMillis;
			this.uptimeMillis = uptimeMillis;
			this.systemCpuPercent = systemCpuPercent;
			this.processCpuPercent = processCpuPercent;
			this.ramUsedBytes = ramUsedBytes;
			this.ramMaxBytes = ramMaxBytes;
			this.totalEntities = totalEntities;
			this.totalLivingEntities = totalLivingEntities;
			this.totalChunks = totalChunks;
		}
	}
}
