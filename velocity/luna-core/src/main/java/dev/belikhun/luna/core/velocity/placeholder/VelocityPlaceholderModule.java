package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.belikhun.luna.core.api.heartbeat.BackendStatusView;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.PlaceholderInspector;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.profile.PermissionService;
import dev.belikhun.luna.core.api.server.ServerDisplayResolver;
import dev.belikhun.luna.core.velocity.VelocityPlayerDisplayFormat;
import dev.belikhun.luna.core.velocity.serverselector.VelocityServerSelectorConfig;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The proxy's placeholder engine and every surface it is exposed on.
 *
 * Other proxy plugins publish specs through {@link #runtime()} and, when a spec
 * takes parameters, tell {@link #enumerateTabIds} which concrete ids TAB should
 * know. This module owns the one {@code /tab reload} hook, samples players on a
 * timer, and on start names every config line still using a pre-{@code lunav}
 * id so the migration is a known list rather than a surprise.
 */
public final class VelocityPlaceholderModule {
	/** Player samples twice a second, which is also the fastest TAB interval used. */
	private static final Duration SAMPLE_PERIOD = Duration.ofMillis(500L);
	/** The alias namespace kept while templates still say {@code <luna_player_status>}. */
	private static final String LEGACY_MINI_NAMESPACE = "luna";

	private final Object plugin;
	private final ProxyServer proxyServer;
	private final Path dataDirectory;
	private final LunaLogger logger;
	private final PlaceholderRuntime<Player> runtime;
	private final PlaceholderInspector<Player> inspector;
	private final VelocityMiniPlaceholdersAdapter miniPlaceholders;
	private final VelocityTabAdapter tab;
	private final VelocityTemplateRenderer templates;
	private ScheduledTask samplerTask;
	private EventHandler<TabLoadEvent> tabLoadHandler;
	private volatile Supplier<VelocityServerSelectorConfig> selectorConfig = () -> null;

	public VelocityPlaceholderModule(Object plugin, ProxyServer proxyServer, Path dataDirectory, PermissionService permissions, LunaLogger logger) {
		this.plugin = plugin;
		this.proxyServer = proxyServer;
		this.dataDirectory = dataDirectory;
		this.logger = logger.scope("Placeholders");
		this.runtime = new PlaceholderRuntime<>(new VelocityPlaceholderHost(proxyServer, permissions), this.logger::warn);
		this.inspector = new PlaceholderInspector<>(runtime);
		this.miniPlaceholders = proxyServer.getPluginManager().getPlugin("miniplaceholders").isPresent()
			? new VelocityMiniPlaceholdersAdapter(runtime, logger)
			: null;
		this.tab = proxyServer.getPluginManager().getPlugin("tab").isPresent()
			? new VelocityTabAdapter(runtime, logger)
			: null;

		this.templates = new VelocityTemplateRenderer(runtime, miniPlaceholders != null, logger);

		if (miniPlaceholders != null) {
			miniPlaceholders.alias(LEGACY_MINI_NAMESPACE, LunavPlaceholders.NAMESPACE);
		}

		tab(adapter -> adapter.enumerate(this::backendIds));
		runtime.onChange(this::syncAdapters);
	}

	/** Called once at proxy start: samples, hooks and the first sync. */
	public void start() {
		runtime.engine().refreshServer();
		samplerTask = proxyServer.getScheduler().buildTask(plugin, this::sample).repeat(SAMPLE_PERIOD).schedule();
		ensureTabReloadHookRegistered();
		scanForLegacyIds();
	}

	/**
	 * Publishes the core's own {@code lunav} specs for the current config. Called
	 * on every reload; the previous registration under the same owner is replaced.
	 */
	public void publishCore(
		BackendStatusView statusView,
		VelocityServerSelectorConfig config,
		ServerDisplayResolver displayResolver,
		VelocityPlayerDisplayFormat displayFormat
	) {
		selectorConfig = () -> config;
		runtime.register("luna-core", new LunavPlaceholders(statusView, selectorConfig, displayResolver, displayFormat).specs());
	}

	public PlaceholderRuntime<Player> runtime() {
		return runtime;
	}

	public PlaceholderInspector<Player> inspector() {
		return inspector;
	}

	/** The one renderer for proxy templates: messenger formats, selector text, display names. */
	public VelocityTemplateRenderer templates() {
		return templates;
	}

	/** Adds a source of concrete TAB ids for specs with parameters; nothing happens without TAB. */
	public void enumerateTabIds(Supplier<Collection<String>> ids) {
		tab(adapter -> adapter.enumerate(ids));
	}

	/** Keeps a pre-{@code lunav} TAB id working as an alias of a canonical one. */
	public void aliasTabId(String legacyId, String canonicalId) {
		tab(adapter -> adapter.alias(legacyId, canonicalId));
	}

	/** Re-registers every surface, for a plugin whose enumeration changed on its own reload. */
	public void syncAdapters() {
		if (miniPlaceholders != null) {
			miniPlaceholders.sync();
		}

		if (tab != null) {
			tab.sync();
		}
	}

	/** The ids currently registered with TAB, for the debug listing. */
	public List<String> tabIds() {
		return tab == null ? List.of() : tab.registeredIds();
	}

	public void shutdown() {
		if (samplerTask != null) {
			samplerTask.cancel();
			samplerTask = null;
		}

		unregisterTabReloadHook();

		if (miniPlaceholders != null) {
			miniPlaceholders.unregisterAll();
		}

		if (tab != null) {
			tab.unregisterAll();
		}
	}

	private void sample() {
		runtime.engine().refreshServer();
		runtime.engine().refreshPlayers(new ArrayList<>(proxyServer.getAllPlayers()));
	}

	/** One {@code %lunav_server_<field>_<backend>%} per server-list backend per field. */
	private Collection<String> backendIds() {
		VelocityServerSelectorConfig config = selectorConfig.get();
		List<String> ids = new ArrayList<>();

		if (config == null) {
			return ids;
		}

		List<String> fields = new ArrayList<>();

		for (var spec : runtime.registry().specs(LunavPlaceholders.NAMESPACE)) {
			String prefix = spec.key().literalPrefix();

			if (prefix.startsWith("server_") && spec.key().hasParams()) {
				fields.add(prefix);
			}
		}

		for (VelocityServerSelectorConfig.ServerDefinition definition : config.servers().values()) {
			String backend = definition.backendName() == null ? "" : definition.backendName().trim().toLowerCase(Locale.ROOT);

			if (backend.isBlank()) {
				continue;
			}

			for (String field : fields) {
				ids.add("%" + LunavPlaceholders.NAMESPACE + "_" + field + "_" + backend + "%");
			}
		}

		return ids;
	}

	private void ensureTabReloadHookRegistered() {
		if (tab == null || tabLoadHandler != null) {
			return;
		}

		try {
			if (TabAPI.getInstance().getEventBus() == null) {
				logger.warn("TAB API event bus không khả dụng. Không thể tự đăng ký lại placeholders sau /tab reload.");
				return;
			}

			// TAB drops every registration on its own reload; this is the one hook
			// that puts them all back, for every luna plugin at once
			tabLoadHandler = event -> {
				try {
					tab.sync();
					logger.audit("Đã đăng ký lại TAB placeholders sau /tab reload.");
				} catch (Throwable throwable) {
					logger.error("Không thể đăng ký lại TAB placeholders sau /tab reload.", throwable);
				}
			};
			TabAPI.getInstance().getEventBus().register(TabLoadEvent.class, tabLoadHandler);
		} catch (Throwable throwable) {
			tabLoadHandler = null;
			logger.warn("Không thể gắn listener TabLoadEvent: " + throwable.getMessage());
		}
	}

	private void unregisterTabReloadHook() {
		if (tabLoadHandler == null) {
			return;
		}

		try {
			if (TabAPI.getInstance().getEventBus() != null) {
				TabAPI.getInstance().getEventBus().unregister(tabLoadHandler);
			}
		} catch (Throwable ignored) {
			// TAB may already be gone at shutdown
		}

		tabLoadHandler = null;
	}

	/** Names every config line on this proxy that still uses a pre-{@code lunav} id. */
	private void scanForLegacyIds() {
		for (Path file : legacyScanTargets()) {
			if (!Files.isRegularFile(file)) {
				continue;
			}

			try {
				Map<String, String> found = LegacyPlaceholderIds.findLegacy(Files.readString(file, StandardCharsets.UTF_8));

				for (Map.Entry<String, String> entry : found.entrySet()) {
					logger.warn("Placeholder cũ trong " + dataDirectory.getParent().relativize(file) + ": " + entry.getKey()
						+ " → dùng " + entry.getValue() + " (chạy /luna placeholder migrate).");
				}
			} catch (IOException exception) {
				logger.debug("Không đọc được " + file + " để quét placeholder cũ: " + exception.getMessage());
			}
		}
	}

	/** The config files the migrate command rewrites and the scan reads. */
	public List<Path> legacyScanTargets() {
		Path plugins = dataDirectory.getParent();
		return List.of(
			plugins.resolve("tab").resolve("config.yml"),
			plugins.resolve("tab").resolve("groups.yml"),
			plugins.resolve("tab").resolve("layout.yml"),
			plugins.resolve("lunamessenger").resolve("config.yml"),
			plugins.resolve("lunacore").resolve("config.yml"),
			plugins.resolve("lunacore").resolve("servers.yml")
		);
	}

	private void tab(java.util.function.Consumer<VelocityTabAdapter> action) {
		if (tab != null) {
			action.accept(tab);
		}
	}
}
