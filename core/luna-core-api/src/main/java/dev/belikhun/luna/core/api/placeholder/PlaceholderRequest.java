package dev.belikhun.luna.core.api.placeholder;

import java.util.Map;
import java.util.function.Function;

/**
 * Everything a resolver may look at for one resolution.
 *
 * @param ref     the placeholder as written
 * @param spec    the spec that matched it
 * @param params  the key parameters bound from the ref, by name
 * @param player  the viewer, or null for a server-scoped or player-less request
 * @param server  the current server sample, never null
 * @param viewer  the viewer's sample, or null when there is no player or no sample yet
 * @param nested  expands {@code {…}} placeholders inside a parameter value, for
 *                the few keys that take another placeholder as their argument
 * @param <P>     the platform's player type
 */
public record PlaceholderRequest<P>(
	PlaceholderRef ref,
	PlaceholderSpec<P> spec,
	Map<String, String> params,
	P player,
	ServerSample server,
	PlayerSample viewer,
	Function<String, String> nested
) {
	/** A bound parameter, or null when the pattern left it unbound. */
	public String param(String name) {
		return params.get(name);
	}

	/** A bound parameter, or the fallback when it is unbound or blank. */
	public String param(String name, String fallback) {
		String value = params.get(name);
		return value == null || value.isEmpty() ? fallback : value;
	}

	/** An integer parameter, or the fallback when unbound or not a number. */
	public int intParam(String name, int fallback) {
		String value = params.get(name);

		if (value == null || value.isBlank()) {
			return fallback;
		}

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	/**
	 * A parameter with any {@code {…}} placeholders inside it expanded, so
	 * {@code %luna_mm2l_{luckperms_prefix}%} sees the prefix rather than the braces.
	 */
	public String expandedParam(String name) {
		String value = params.get(name);

		if (value == null || nested == null || value.indexOf('{') < 0) {
			return value;
		}

		return nested.apply(value);
	}

	/** True when a viewer is present and sampled. */
	public boolean hasViewer() {
		return viewer != null;
	}
}
