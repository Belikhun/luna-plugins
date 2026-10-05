package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.PlaceholderGrammar;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRef;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRefresh;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRegistry;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.placeholder.PlaceholderScope;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSpec;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSyntax;
import dev.belikhun.luna.core.api.placeholder.Resolution;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.placeholder.PlaceholderManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Every registry spec as a TAB placeholder.
 *
 * TAB needs concrete ids, so a spec with parameters is registered once per
 * binding an enumeration supplies: the core enumerates its backends, the glyph
 * plugin its glyph keys. Legacy ids from before the {@code lunav} namespace
 * are registered too, resolving to their canonical form, until the configs
 * are migrated.
 */
public final class VelocityTabAdapter {
	private static final int STATIC_INTERVAL_MILLIS = 10_000;

	private final PlaceholderRuntime<Player> runtime;
	private final LunaLogger logger;
	private final List<Supplier<Collection<String>>> enumerations = new CopyOnWriteArrayList<>();
	private final Map<String, String> legacyIds = new LinkedHashMap<>();
	private final List<String> registered = new ArrayList<>();

	public VelocityTabAdapter(PlaceholderRuntime<Player> runtime, LunaLogger logger) {
		this.runtime = runtime;
		this.logger = logger.scope("TAB");
	}

	/** Adds a source of concrete ids for specs that take parameters. */
	public void enumerate(Supplier<Collection<String>> ids) {
		enumerations.add(ids);
	}

	/** Registers a pre-{@code lunav} id that resolves as its canonical form. */
	public void alias(String legacyId, String canonicalId) {
		legacyIds.put(legacyId, canonicalId);
	}

	/** Every id currently registered with TAB. */
	public synchronized List<String> registeredIds() {
		return List.copyOf(registered);
	}

	/** Rebuilds every TAB registration from the registry and the enumerations. */
	public synchronized void sync() {
		unregisterAll();
		PlaceholderManager manager = TabAPI.getInstance().getPlaceholderManager();
		Set<String> ids = new LinkedHashSet<>();

		for (Map.Entry<String, List<PlaceholderRegistry.Entry<Player>>> namespace : runtime.registry().entries().entrySet()) {
			for (PlaceholderRegistry.Entry<Player> entry : namespace.getValue()) {
				if (!entry.spec().key().hasParams()) {
					ids.add(PlaceholderGrammar.format(new PlaceholderRef(namespace.getKey(), List.of(entry.spec().key().source().split("_")), List.of(), false, PlaceholderSyntax.PERCENT, ""), PlaceholderSyntax.PERCENT));
				}
			}
		}

		for (Supplier<Collection<String>> enumeration : enumerations) {
			try {
				ids.addAll(enumeration.get());
			} catch (RuntimeException exception) {
				logger.warn("Một nguồn id TAB ném lỗi: " + exception);
			}
		}

		for (String id : ids) {
			register(manager, id, id);

			// the hyphenated spelling the ids had before the rename
			if (id.startsWith("%lunav_")) {
				register(manager, "%lunav-" + id.substring("%lunav_".length(), id.length() - 1).replace('_', '-') + "%", id);
			}
		}

		for (Map.Entry<String, String> legacy : legacyIds.entrySet()) {
			register(manager, legacy.getKey(), legacy.getValue());
		}

		logger.success("Đã đăng ký " + registered.size() + " TAB placeholder (" + ids.size() + " id chính tắc).");
	}

	/** Withdraws every TAB registration. */
	public synchronized void unregisterAll() {
		if (registered.isEmpty()) {
			return;
		}

		try {
			PlaceholderManager manager = TabAPI.getInstance().getPlaceholderManager();

			for (String id : registered) {
				try {
					manager.unregisterPlaceholder(id);
				} catch (Throwable ignored) {
					// TAB drops registrations on its own reload; a second unregister is harmless
				}
			}
		} catch (Throwable throwable) {
			logger.debug("Không thể gỡ TAB placeholders: " + throwable);
		}

		registered.clear();
	}

	private void register(PlaceholderManager manager, String tabId, String canonicalId) {
		PlaceholderRef ref = PlaceholderGrammar.parse(canonicalId);
		PlaceholderSpec<Player> spec = runtime.registry().match(ref).map(PlaceholderRegistry.Match::spec).orElse(null);

		if (spec == null) {
			logger.warn("Bỏ qua TAB id " + tabId + ": không có spec cho " + canonicalId + ".");
			return;
		}

		int interval = interval(spec.refresh());

		try {
			if (spec.scope() == PlaceholderScope.SERVER) {
				manager.registerServerPlaceholder(tabId, interval, () -> value(ref, null, tabId));
			} else {
				manager.registerPlayerPlaceholder(tabId, interval, tabPlayer -> value(ref, velocityPlayer(tabPlayer), tabId));
			}

			registered.add(tabId);
		} catch (Throwable throwable) {
			logger.error("Không thể đăng ký TAB placeholder " + tabId + ".", throwable);
		}
	}

	private String value(PlaceholderRef ref, Player player, String tabId) {
		Resolution<Player> resolution = runtime.engine().resolve(ref, player);
		return resolution.hasValue() ? resolution.value() : tabId;
	}

	private static int interval(PlaceholderRefresh refresh) {
		return switch (refresh) {
			case STATIC -> STATIC_INTERVAL_MILLIS;
			case SERVER -> 1000;
			case PLAYER, LIVE -> 500;
		};
	}

	private static Player velocityPlayer(TabPlayer tabPlayer) {
		if (tabPlayer == null) {
			return null;
		}

		Object platformPlayer = tabPlayer.getPlayer();
		return platformPlayer instanceof Player player ? player : null;
	}
}
