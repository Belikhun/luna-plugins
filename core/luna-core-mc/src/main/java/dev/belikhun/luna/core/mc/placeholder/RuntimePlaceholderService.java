package dev.belikhun.luna.core.mc.placeholder;

import dev.belikhun.luna.core.api.placeholder.PlaceholderGrammar;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.placeholder.Resolution;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@link PlaceholderService} over a {@link PlaceholderRuntime}. */
public final class RuntimePlaceholderService implements PlaceholderService {
	private final PlaceholderRuntime<ServerPlayer> runtime;
	private final Runnable ensureSampled;

	public RuntimePlaceholderService(PlaceholderRuntime<ServerPlayer> runtime, Runnable ensureSampled) {
		this.runtime = runtime;
		this.ensureSampled = ensureSampled;
	}

	@Override
	public PlaceholderRuntime<ServerPlayer> runtime() {
		return runtime;
	}

	@Override
	public void refreshSharedSnapshot() {
		ensureSampled.run();
	}

	@Override
	public Map<String, String> snapshot(ServerPlayer player, Collection<String> requestedIdentifiers) {
		Map<String, String> values = new LinkedHashMap<>();

		if (requestedIdentifiers == null) {
			return values;
		}

		for (String identifier : requestedIdentifiers) {
			String value = resolvePlaceholder(player, identifier);

			if (value != null) {
				values.put(identifier, value);
			}
		}

		return values;
	}

	@Override
	public String resolvePlaceholder(ServerPlayer player, String identifier) {
		if (identifier == null || identifier.isBlank()) {
			return null;
		}

		// relational placeholders are the bridge's own affair
		if (identifier.startsWith("%rel_") || identifier.startsWith("rel_")) {
			return null;
		}

		Resolution<ServerPlayer> resolution = runtime.engine().resolve(PlaceholderGrammar.parse(identifier), player);
		return resolution.hasValue() ? resolution.value() : null;
	}
}
