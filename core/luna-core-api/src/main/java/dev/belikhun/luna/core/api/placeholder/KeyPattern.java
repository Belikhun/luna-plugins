package dev.belikhun.luna.core.api.placeholder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The shape of one placeholder key: literal words and typed parameters.
 *
 * Written as it would appear in a config, with parameters in angle brackets and
 * optional trailing parts in square brackets:
 * <pre>
 *   tps_bar[_&lt;width:width&gt;]
 *   world_&lt;world:text&gt;_weather
 *   player_status[_&lt;dot:rest&gt;]
 *   mm2l_&lt;value:rest&gt;
 * </pre>
 * Matching is against a ref's tokens first and its explicit arguments second,
 * so {@code %luna_tps_bar_10%} and {@code <luna_tps_bar:10>} bind the same
 * width. Literal words match case-insensitively; a bound parameter keeps the
 * writer's case.
 */
public final class KeyPattern {
	private final List<Element> elements;
	private final String source;
	private final int literalCount;
	private final int requiredParamCount;

	private KeyPattern(List<Element> elements, String source) {
		this.elements = List.copyOf(elements);
		this.source = source;
		int literals = 0;
		int required = 0;

		for (Element element : elements) {
			if (element.literal != null) {
				literals++;
			} else if (!element.optional) {
				required++;
			}
		}

		this.literalCount = literals;
		this.requiredParamCount = required;
	}

	/**
	 * Parses a pattern.
	 *
	 * @param pattern the pattern text
	 * @return the pattern
	 * @throws IllegalArgumentException when the text is malformed
	 */
	public static KeyPattern parse(String pattern) {
		if (pattern == null || pattern.isBlank()) {
			return new KeyPattern(List.of(), "");
		}

		List<Element> elements = new ArrayList<>();
		String remaining = pattern.trim();
		boolean optional = false;
		int index = 0;

		while (index < remaining.length()) {
			char character = remaining.charAt(index);

			if (character == '[') {
				optional = true;
				index++;
				continue;
			}

			if (character == ']') {
				optional = false;
				index++;
				continue;
			}

			if (character == '_') {
				index++;
				continue;
			}

			if (character == '<') {
				int close = remaining.indexOf('>', index);

				if (close < 0) {
					throw new IllegalArgumentException("Unclosed parameter in key pattern: " + pattern);
				}

				String[] parts = remaining.substring(index + 1, close).split(":", 2);
				String name = parts[0].trim();
				ParamType type = parts.length > 1 ? ParamType.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)) : ParamType.WORD;

				if (name.isEmpty()) {
					throw new IllegalArgumentException("Unnamed parameter in key pattern: " + pattern);
				}

				elements.add(Element.param(name, type, optional));
				index = close + 1;
				continue;
			}

			int end = index;

			while (end < remaining.length() && "_[]<>".indexOf(remaining.charAt(end)) < 0) {
				end++;
			}

			elements.add(Element.literal(remaining.substring(index, end), optional));
			index = end;
		}

		for (int position = 0; position < elements.size(); position++) {
			Element element = elements.get(position);

			if (element.type == ParamType.REST && position != elements.size() - 1) {
				throw new IllegalArgumentException("A rest parameter must be last: " + pattern);
			}
		}

		return new KeyPattern(elements, remaining);
	}

	/** The pattern as written. */
	public String source() {
		return source;
	}

	/** How many literal words the pattern has; more literals means a more specific pattern. */
	public int literalCount() {
		return literalCount;
	}

	/** The literal words in order, lowercase, for indexing. */
	public List<String> literals() {
		List<String> literals = new ArrayList<>();

		for (Element element : elements) {
			if (element.literal != null) {
				literals.add(element.literal);
			}
		}

		return literals;
	}

	/**
	 * The literal words before the first parameter, joined with underscores:
	 * the part of the key a consumer that cannot express parameters, such as a
	 * MiniMessage tag name, registers under. Empty when the pattern starts with
	 * a parameter.
	 */
	public String literalPrefix() {
		StringBuilder prefix = new StringBuilder();

		for (Element element : elements) {
			if (element.literal == null) {
				break;
			}

			if (prefix.length() > 0) {
				prefix.append('_');
			}

			prefix.append(element.literal);
		}

		return prefix.toString();
	}

	/** The first literal word, or empty when the pattern starts with a parameter. */
	public String head() {
		for (Element element : elements) {
			if (element.literal != null) {
				return element.literal;
			}

			break;
		}

		return "";
	}

	/** The parameter names in order. */
	public List<String> paramNames() {
		List<String> names = new ArrayList<>();

		for (Element element : elements) {
			if (element.name != null) {
				names.add(element.name);
			}
		}

		return names;
	}

	/**
	 * Whether the pattern has parameters at all; a pattern with none matches
	 * exactly one identifier.
	 */
	public boolean hasParams() {
		return paramNames().size() > 0;
	}

	/**
	 * Tries to bind the pattern to a ref's tokens and explicit arguments.
	 *
	 * @param tokens the ref's tokens after the namespace
	 * @param args   the ref's explicit arguments
	 * @return the bound parameters by name, or empty when the pattern does not match
	 */
	public Optional<Map<String, String>> match(List<String> tokens, List<String> args) {
		List<String> input = new ArrayList<>(tokens);
		Map<String, String> bound = new LinkedHashMap<>();
		Optional<Integer> consumed = matchFrom(0, input, 0, bound);

		if (consumed.isEmpty()) {
			return Optional.empty();
		}

		int position = consumed.get();

		if (position < input.size()) {
			return Optional.empty();
		}

		// explicit arguments bind the parameters the tokens left open, in order
		int argIndex = 0;

		for (Element element : elements) {
			if (element.name == null || bound.containsKey(element.name)) {
				continue;
			}

			if (argIndex < args.size()) {
				String value = element.type == ParamType.REST
					? String.join("_", args.subList(argIndex, args.size()))
					: args.get(argIndex);
				argIndex = element.type == ParamType.REST ? args.size() : argIndex + 1;

				if (!element.type.accepts(value)) {
					return Optional.empty();
				}

				bound.put(element.name, value);
				continue;
			}

			if (!element.optional) {
				return Optional.empty();
			}
		}

		if (argIndex < args.size()) {
			return Optional.empty();
		}

		return Optional.of(bound);
	}

	private Optional<Integer> matchFrom(int elementIndex, List<String> input, int position, Map<String, String> bound) {
		if (elementIndex >= elements.size()) {
			return Optional.of(position);
		}

		Element element = elements.get(elementIndex);

		if (element.literal != null) {
			if (position < input.size() && element.literal.equalsIgnoreCase(input.get(position))) {
				return matchFrom(elementIndex + 1, input, position + 1, bound);
			}

			if (element.optional) {
				return skipOptionalRun(elementIndex, input, position, bound);
			}

			return Optional.empty();
		}

		if (position >= input.size()) {
			// parameters left unbound may still bind from explicit arguments later
			return Optional.of(position);
		}

		if (element.type == ParamType.REST) {
			String rest = String.join("_", input.subList(position, input.size()));
			bound.put(element.name, rest);
			return Optional.of(input.size());
		}

		if (element.type == ParamType.TEXT) {
			// greedy, backing off one token at a time until the tail matches
			for (int end = input.size(); end > position; end--) {
				String candidate = String.join("_", input.subList(position, end));
				Map<String, String> attempt = new LinkedHashMap<>(bound);
				attempt.put(element.name, candidate);
				Optional<Integer> result = matchFrom(elementIndex + 1, input, end, attempt);

				if (result.isPresent() && result.get() == input.size()) {
					bound.clear();
					bound.putAll(attempt);
					return result;
				}
			}

			return Optional.empty();
		}

		String value = input.get(position);

		if (!element.type.accepts(value)) {
			if (element.optional) {
				return skipOptionalRun(elementIndex, input, position, bound);
			}

			return Optional.empty();
		}

		bound.put(element.name, value);
		return matchFrom(elementIndex + 1, input, position + 1, bound);
	}

	private Optional<Integer> skipOptionalRun(int elementIndex, List<String> input, int position, Map<String, String> bound) {
		int next = elementIndex;

		while (next < elements.size() && elements.get(next).optional) {
			next++;
		}

		return matchFrom(next, input, position, bound);
	}

	@Override
	public String toString() {
		return source;
	}

	/** The pattern's identity is its text; two specs with the same text collide. */
	@Override
	public boolean equals(Object other) {
		return other instanceof KeyPattern pattern && pattern.source.equalsIgnoreCase(source);
	}

	@Override
	public int hashCode() {
		return source.toLowerCase(Locale.ROOT).hashCode();
	}

	int requiredParamCount() {
		return requiredParamCount;
	}

	private record Element(String literal, String name, ParamType type, boolean optional) {
		static Element literal(String text, boolean optional) {
			return new Element(text.toLowerCase(Locale.ROOT), null, null, optional);
		}

		static Element param(String name, ParamType type, boolean optional) {
			return new Element(null, name, type, optional);
		}
	}
}
