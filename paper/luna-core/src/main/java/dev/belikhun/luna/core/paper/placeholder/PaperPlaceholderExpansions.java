package dev.belikhun.luna.core.paper.placeholder;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.PlaceholderGrammar;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRef;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.placeholder.Resolution;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * One PlaceholderAPI expansion per namespace the registry claims, all
 * delegating to the engine.
 *
 * A plugin that registers specs never touches PlaceholderAPI itself: the
 * expansion for its namespace appears when the runtime reports a change and
 * goes away when the last spec in that namespace is withdrawn.
 */
public final class PaperPlaceholderExpansions {
	private final JavaPlugin plugin;
	private final PlaceholderRuntime<OfflinePlayer> runtime;
	private final LunaLogger logger;
	private final Map<String, NamespaceExpansion> expansions = new HashMap<>();

	public PaperPlaceholderExpansions(JavaPlugin plugin, PlaceholderRuntime<OfflinePlayer> runtime, LunaLogger logger) {
		this.plugin = plugin;
		this.runtime = runtime;
		this.logger = logger.scope("PlaceholderAPI");
	}

	/** Brings the set of registered expansions in line with the registry. */
	public synchronized void sync() {
		Set<String> wanted = runtime.registry().namespaces();

		for (String namespace : wanted) {
			if (expansions.containsKey(namespace)) {
				continue;
			}

			NamespaceExpansion expansion = new NamespaceExpansion(namespace);

			try {
				if (expansion.register()) {
					expansions.put(namespace, expansion);
					logger.success("Đã đăng ký PlaceholderAPI namespace %" + namespace + "_...%.");
				} else {
					logger.warn("PlaceholderAPI từ chối namespace " + namespace + ".");
				}
			} catch (Throwable throwable) {
				logger.error("Không thể đăng ký PlaceholderAPI expansion " + namespace + ".", throwable);
			}
		}

		expansions.entrySet().removeIf(entry -> {
			if (wanted.contains(entry.getKey())) {
				return false;
			}

			unregisterQuietly(entry.getValue());
			return true;
		});
	}

	/** Withdraws every expansion, on disable. */
	public synchronized void unregisterAll() {
		for (NamespaceExpansion expansion : expansions.values()) {
			unregisterQuietly(expansion);
		}

		expansions.clear();
	}

	private void unregisterQuietly(NamespaceExpansion expansion) {
		try {
			if (expansion.isRegistered()) {
				expansion.unregister();
			}
		} catch (Throwable throwable) {
			logger.debug("Không thể gỡ PlaceholderAPI expansion " + expansion.getIdentifier() + ": " + throwable);
		}
	}

	private final class NamespaceExpansion extends PlaceholderExpansion {
		private final String namespace;

		private NamespaceExpansion(String namespace) {
			this.namespace = namespace;
		}

		@Override
		public String getIdentifier() {
			return namespace;
		}

		@Override
		public String getAuthor() {
			return "Belikhun";
		}

		@Override
		public String getVersion() {
			return plugin.getPluginMeta().getVersion();
		}

		@Override
		public boolean persist() {
			return true;
		}

		@Override
		public String onRequest(OfflinePlayer player, String params) {
			if (params == null || params.isBlank()) {
				return null;
			}

			PlaceholderRef ref = PlaceholderGrammar.parse("%" + namespace + "_" + params.trim() + "%");
			Resolution<OfflinePlayer> resolution = runtime.engine().resolve(ref, player);

			// null tells PlaceholderAPI the key is not ours, so the text stays visible
			return resolution.hasValue() ? resolution.value() : null;
		}
	}
}
