package dev.belikhun.luna.core.api.placeholder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Resolves placeholders against the registry, reading only samples.
 *
 * The platform drives the two refresh calls on the server thread; everything
 * else may be called from any thread. The engine owns no timer of its own so
 * each platform can use its scheduler.
 *
 * @param <P> the platform's player type
 */
public final class PlaceholderEngine<P> {
	/** How deep {@code {…}} expansion inside a parameter may recurse. */
	private static final int MAX_NESTED_DEPTH = 4;

	private final PlaceholderRegistry<P> registry;
	private final PlaceholderHost<P> host;
	private final Consumer<String> diagnostics;
	private final Map<UUID, PlayerSample> players = new ConcurrentHashMap<>();
	private volatile ServerSample server = ServerSample.empty();

	public PlaceholderEngine(PlaceholderRegistry<P> registry, PlaceholderHost<P> host, Consumer<String> diagnostics) {
		this.registry = registry;
		this.host = host;
		this.diagnostics = diagnostics == null ? message -> {
		} : diagnostics;
	}

	public PlaceholderRegistry<P> registry() {
		return registry;
	}

	public PlaceholderHost<P> host() {
		return host;
	}

	/** The current server sample, never null. */
	public ServerSample server() {
		return server;
	}

	/** A player's latest sample, or null when none was taken yet. */
	public PlayerSample viewer(P player) {
		if (player == null) {
			return null;
		}

		return players.get(host.idOf(player));
	}

	/** A player's latest sample by id, or null. */
	public PlayerSample viewer(UUID playerId) {
		return playerId == null ? null : players.get(playerId);
	}

	/** Re-samples the server. Call on the server thread. */
	public void refreshServer() {
		try {
			ServerSample sample = host.sampleServer();

			if (sample != null) {
				server = sample;
			}
		} catch (RuntimeException exception) {
			diagnostics.accept("Lấy mẫu server cho placeholder thất bại: " + exception);
		}
	}

	/** Re-samples one player. Call on the server thread. */
	public void refreshPlayer(P player) {
		if (player == null) {
			return;
		}

		try {
			PlayerSample sample = host.samplePlayer(player);

			if (sample != null) {
				players.put(sample.id(), sample);
			}
		} catch (RuntimeException exception) {
			diagnostics.accept("Lấy mẫu người chơi " + host.nameOf(player) + " cho placeholder thất bại: " + exception);
		}
	}

	/** Re-samples every player given and forgets the ones that are not in the list. */
	public void refreshPlayers(Collection<? extends P> online) {
		List<UUID> seen = new ArrayList<>();

		for (P player : online) {
			refreshPlayer(player);
			seen.add(host.idOf(player));
		}

		players.keySet().retainAll(seen);
	}

	/** Drops a player's sample, on quit. */
	public void forgetPlayer(UUID playerId) {
		if (playerId != null) {
			players.remove(playerId);
		}
	}

	/** How many players currently have a sample. */
	public int sampledPlayers() {
		return players.size();
	}

	/**
	 * Resolves one placeholder.
	 *
	 * @param ref    the placeholder as written
	 * @param player the viewer, or null
	 * @return the outcome, never null
	 */
	public Resolution<P> resolve(PlaceholderRef ref, P player) {
		return resolve(ref, player, 0);
	}

	/**
	 * Resolves an identifier written in any syntax.
	 *
	 * @return the value, or null when nothing claims it
	 */
	public String resolveText(String identifier, P player) {
		Resolution<P> resolution = resolve(PlaceholderGrammar.parse(identifier), player);
		return resolution.hasValue() ? resolution.value() : null;
	}

	/**
	 * Resolves several identifiers at once, keeping the caller's spelling as the key.
	 */
	public Map<String, Resolution<P>> resolveAll(Collection<String> identifiers, P player) {
		Map<String, Resolution<P>> results = new LinkedHashMap<>();

		for (String identifier : identifiers) {
			results.put(identifier, resolve(PlaceholderGrammar.parse(identifier), player));
		}

		return results;
	}

	/**
	 * Replaces every {@code {…}} placeholder in text with its value, leaving the
	 * ones nothing claims untouched.
	 */
	public String expandBraces(String text, P player) {
		return expandBraces(text, player, 0);
	}

	private Resolution<P> resolve(PlaceholderRef ref, P player, int depth) {
		long started = System.nanoTime();

		if (ref == null || ref.namespace().isEmpty()) {
			return new Resolution<>(ref, Resolution.Status.UNMATCHED, null, null, Map.of(), System.nanoTime() - started, null);
		}

		Optional<PlaceholderRegistry.Match<P>> match = registry.match(ref);

		if (match.isEmpty()) {
			return foreign(ref, player, started);
		}

		PlaceholderSpec<P> spec = match.get().spec();
		Map<String, String> params = match.get().params();
		PlaceholderRequest<P> request = new PlaceholderRequest<>(
			ref,
			spec,
			params,
			player,
			server,
			viewer(player),
			value -> expandBraces(value, player, depth + 1)
		);

		try {
			String value = spec.resolver().resolve(request);

			if (value == null) {
				return new Resolution<>(ref, Resolution.Status.EMPTY, null, spec, params, System.nanoTime() - started, null);
			}

			if (ref.safe()) {
				value = PlaceholderEscaping.escapePercents(value);
			}

			return new Resolution<>(ref, Resolution.Status.RESOLVED, value, spec, params, System.nanoTime() - started, null);
		} catch (RuntimeException exception) {
			diagnostics.accept("Placeholder " + ref.source() + " (" + spec.canonical() + ") ném lỗi: " + exception);
			return new Resolution<>(ref, Resolution.Status.ERROR, null, spec, params, System.nanoTime() - started, String.valueOf(exception));
		}
	}

	private Resolution<P> foreign(PlaceholderRef ref, P player, long started) {
		try {
			String value = host.foreign(player, ref);

			if (value == null) {
				return new Resolution<>(ref, Resolution.Status.UNMATCHED, null, null, Map.of(), System.nanoTime() - started, null);
			}

			if (ref.safe()) {
				value = PlaceholderEscaping.escapePercents(value);
			}

			return new Resolution<>(ref, Resolution.Status.FOREIGN, value, null, Map.of(), System.nanoTime() - started, null);
		} catch (RuntimeException exception) {
			return new Resolution<>(ref, Resolution.Status.ERROR, null, null, Map.of(), System.nanoTime() - started, String.valueOf(exception));
		}
	}

	private String expandBraces(String text, P player, int depth) {
		if (text == null || text.indexOf('{') < 0 || depth > MAX_NESTED_DEPTH) {
			return text;
		}

		StringBuilder output = new StringBuilder();

		for (PlaceholderGrammar.Segment segment : PlaceholderGrammar.scan(text, EnumSet.of(PlaceholderSyntax.BRACE))) {
			if (!segment.isRef()) {
				output.append(segment.literal());
				continue;
			}

			Resolution<P> resolution = resolve(segment.ref(), player, depth + 1);
			output.append(resolution.hasValue() ? resolution.value() : segment.source());
		}

		return output.toString();
	}
}
