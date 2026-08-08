package dev.belikhun.luna.core.api.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JSON writer for HTTP endpoints.
 *
 * The stack deliberately ships no JSON library on either platform, so responses
 * are serialized from plain {@link Map}/{@link Iterable}/primitive graphs here
 * instead of being hand-assembled per endpoint. Non-finite doubles are written as
 * {@code null}, since {@code NaN} and {@code Infinity} are not valid JSON and
 * appear naturally in metrics that divide by a zero maximum.
 */
public final class LunaJson {
	private LunaJson() {
	}

	/** Start an ordered object builder. */
	public static Obj obj() {
		return new Obj();
	}

	/** Start an array builder. */
	public static Arr arr() {
		return new Arr();
	}

	/** Serialize any supported value graph to JSON text. */
	public static String write(Object value) {
		StringBuilder out = new StringBuilder();
		writeValue(out, value);
		return out.toString();
	}

	/**
	 * Wrap a payload in the envelope every Luna endpoint returns:
	 * {@code {success, runtimeMillis, data}} on success, or the payload's own
	 * fields merged next to {@code success} on failure.
	 *
	 * @param status     HTTP status code, deciding which shape is used
	 * @param payload    body fields
	 * @param startedNanos value of {@link System#nanoTime()} when handling began
	 */
	public static HttpResponse envelope(int status, Map<String, Object> payload, long startedNanos) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("success", status < 400);
		envelope.put("runtimeMillis", round((System.nanoTime() - startedNanos) / 1_000_000D));

		if (status < 400) {
			envelope.put("data", payload == null ? Map.of() : payload);
		} else if (payload != null) {
			envelope.putAll(payload);
		}

		return HttpResponse.json(status, write(envelope));
	}

	/** Envelope for an error status, with a single {@code error} message. */
	public static HttpResponse error(int status, String message) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("error", message == null ? "" : message);
		return envelope(status, payload, System.nanoTime());
	}

	/** Round to two decimals, folding non-finite values to zero. */
	public static double round(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0D;
		}
		return Math.round(value * 100D) / 100D;
	}

	private static void writeValue(StringBuilder out, Object value) {
		if (value == null) {
			out.append("null");
			return;
		}

		if (value instanceof Obj objValue) {
			writeValue(out, objValue.values);
			return;
		}

		if (value instanceof Arr arrValue) {
			writeValue(out, arrValue.values);
			return;
		}

		if (value instanceof String stringValue) {
			writeString(out, stringValue);
			return;
		}

		if (value instanceof Character characterValue) {
			writeString(out, String.valueOf(characterValue));
			return;
		}

		if (value instanceof Double || value instanceof Float) {
			double doubleValue = ((Number) value).doubleValue();
			if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
				out.append("null");
			} else {
				out.append(doubleValue);
			}
			return;
		}

		if (value instanceof Number numberValue) {
			out.append(numberValue);
			return;
		}

		if (value instanceof Boolean booleanValue) {
			out.append(booleanValue.booleanValue());
			return;
		}

		if (value instanceof Enum<?> enumValue) {
			writeString(out, enumValue.name());
			return;
		}

		if (value instanceof Map<?, ?> mapValue) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				writeString(out, String.valueOf(entry.getKey()));
				out.append(':');
				writeValue(out, entry.getValue());
			}
			out.append('}');
			return;
		}

		if (value instanceof Iterable<?> iterableValue) {
			out.append('[');
			boolean first = true;
			for (Object item : iterableValue) {
				if (!first) {
					out.append(',');
				}
				first = false;
				writeValue(out, item);
			}
			out.append(']');
			return;
		}

		if (value instanceof Object[] arrayValue) {
			out.append('[');
			for (int index = 0; index < arrayValue.length; index++) {
				if (index > 0) {
					out.append(',');
				}
				writeValue(out, arrayValue[index]);
			}
			out.append(']');
			return;
		}

		writeString(out, String.valueOf(value));
	}

	private static void writeString(StringBuilder out, String value) {
		out.append('"');
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					// control characters break JSON; U+2028/2029 break naive JS consumers.
					// Written numerically because a unicode escape for a line separator is
					// itself a line terminator to the Java lexer.
					if (character < 0x20 || character == 0x2028 || character == 0x2029) {
						out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
					} else {
						out.append(character);
					}
				}
			}
		}
		out.append('"');
	}

	/** Insertion-ordered JSON object builder. */
	public static final class Obj {
		private final Map<String, Object> values = new LinkedHashMap<>();

		private Obj() {
		}

		/** Add or replace a field. */
		public Obj put(String key, Object value) {
			values.put(key, value);
			return this;
		}

		/** Add a field only when the value is neither null nor a blank string. */
		public Obj putIfPresent(String key, Object value) {
			if (value == null) {
				return this;
			}
			if (value instanceof String stringValue && stringValue.isBlank()) {
				return this;
			}
			return put(key, value);
		}

		/** Add a double, rounded to two decimals. */
		public Obj putRounded(String key, double value) {
			return put(key, round(value));
		}

		/** The underlying map, as a live view used by the serializer. */
		public Map<String, Object> map() {
			return values;
		}

		@Override
		public String toString() {
			return write(values);
		}
	}

	/** JSON array builder. */
	public static final class Arr {
		private final List<Object> values = new ArrayList<>();

		private Arr() {
		}

		/** Append one element. */
		public Arr add(Object value) {
			values.add(value);
			return this;
		}

		/** Append every element of an iterable. */
		public Arr addAll(Iterable<?> items) {
			if (items != null) {
				for (Object item : items) {
					values.add(item);
				}
			}
			return this;
		}

		/** Number of elements. */
		public int size() {
			return values.size();
		}

		/** The underlying list, as a live view used by the serializer. */
		public List<Object> list() {
			return values;
		}

		@Override
		public String toString() {
			return write(values);
		}
	}
}
