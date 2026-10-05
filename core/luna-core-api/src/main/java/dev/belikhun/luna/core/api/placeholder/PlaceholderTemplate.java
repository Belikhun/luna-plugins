package dev.belikhun.luna.core.api.placeholder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A piece of text with placeholders in it, parsed once and rendered many times.
 *
 * Rendering walks the original segments and never rescans a value, so a value
 * that happens to contain {@code %} or braces is inserted as-is. A ref the
 * resolver returns null for keeps its original text, which is how a typo in a
 * config stays visible instead of vanishing.
 */
public final class PlaceholderTemplate {
	/** Parsed templates kept for callers that render the same config line on every refresh. */
	private static final int CACHE_LIMIT = 1024;
	private static final Map<String, PlaceholderTemplate> CACHE = new ConcurrentHashMap<>();

	private final String source;
	private final List<PlaceholderGrammar.Segment> segments;

	private PlaceholderTemplate(String source, List<PlaceholderGrammar.Segment> segments) {
		this.source = source;
		this.segments = List.copyOf(segments);
	}

	/**
	 * Parses text for the given syntaxes.
	 *
	 * @param text     the template text
	 * @param syntaxes which forms count as placeholders
	 * @return the template
	 */
	public static PlaceholderTemplate parse(String text, Set<PlaceholderSyntax> syntaxes) {
		String safe = text == null ? "" : text;
		return new PlaceholderTemplate(safe, PlaceholderGrammar.scan(safe, syntaxes));
	}

	/**
	 * Parses through a bounded cache keyed by text and syntax set, for hot paths
	 * such as a menu that renders the same lines every refresh.
	 */
	public static PlaceholderTemplate cached(String text, Set<PlaceholderSyntax> syntaxes) {
		String safe = text == null ? "" : text;
		String key = syntaxes.hashCode() + "\u0000" + safe;
		PlaceholderTemplate template = CACHE.get(key);

		if (template != null) {
			return template;
		}

		if (CACHE.size() >= CACHE_LIMIT) {
			CACHE.clear();
		}

		template = parse(safe, syntaxes);
		CACHE.put(key, template);
		return template;
	}

	/**
	 * Renders with plain maps as layers: a ref resolves to the first map holding
	 * its identifier, written either as {@code ns_key} or exactly as typed.
	 */
	@SafeVarargs
	public final String renderValues(Map<String, String>... layers) {
		return render(ref -> {
			String inner = ref.source().length() >= 2 ? ref.source().substring(1, ref.source().length() - 1) : ref.source();

			for (Map<String, String> layer : layers) {
				if (layer == null) {
					continue;
				}

				String value = layer.get(inner);

				if (value == null) {
					value = layer.get(ref.identifier());
				}

				if (value != null) {
					return value;
				}
			}

			return null;
		});
	}

	/** The template as given. */
	public String source() {
		return source;
	}

	/** True when the template holds no placeholders at all. */
	public boolean isLiteral() {
		for (PlaceholderGrammar.Segment segment : segments) {
			if (segment.isRef()) {
				return false;
			}
		}

		return true;
	}

	/** Every distinct placeholder in the template, in first-seen order. */
	public Set<PlaceholderRef> refs() {
		Set<PlaceholderRef> refs = new LinkedHashSet<>();

		for (PlaceholderGrammar.Segment segment : segments) {
			if (segment.isRef()) {
				refs.add(segment.ref());
			}
		}

		return refs;
	}

	/** The segments in order, for callers that render into something other than a string. */
	public List<PlaceholderGrammar.Segment> segments() {
		return segments;
	}

	/**
	 * Renders the template.
	 *
	 * @param resolver gives a value for a ref, or null to keep the original text
	 * @return the rendered text
	 */
	public String render(Function<PlaceholderRef, String> resolver) {
		StringBuilder output = new StringBuilder(source.length() + 32);

		for (PlaceholderGrammar.Segment segment : segments) {
			if (!segment.isRef()) {
				output.append(segment.literal());
				continue;
			}

			String value = resolver.apply(segment.ref());
			output.append(value == null ? segment.source() : value);
		}

		return output.toString();
	}

	/**
	 * Renders with several resolvers in order; the first non-null value wins.
	 */
	@SafeVarargs
	public final String render(Function<PlaceholderRef, String>... layers) {
		List<Function<PlaceholderRef, String>> ordered = new ArrayList<>(List.of(layers));

		return render(ref -> {
			for (Function<PlaceholderRef, String> layer : ordered) {
				String value = layer.apply(ref);

				if (value != null) {
					return value;
				}
			}

			return null;
		});
	}
}
