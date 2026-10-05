package dev.belikhun.luna.core.api.placeholder;

import java.util.Map;

/**
 * The outcome of resolving one placeholder, with enough detail to debug it.
 *
 * @param ref      the placeholder as written
 * @param status   what happened
 * @param value    the value, escaped when the ref asked for it; null unless RESOLVED or FOREIGN
 * @param spec     the matched spec, null when nothing matched
 * @param params   the bound parameters, empty when nothing matched
 * @param nanos    how long resolution took
 * @param error    the exception message when status is ERROR
 */
public record Resolution<P>(
	PlaceholderRef ref,
	Status status,
	String value,
	PlaceholderSpec<P> spec,
	Map<String, String> params,
	long nanos,
	String error
) {
	public enum Status {
		/** A spec matched and returned a value. */
		RESOLVED,
		/** A spec matched but returned null; the caller keeps the placeholder text. */
		EMPTY,
		/** No spec in the registry claims this ref. */
		UNMATCHED,
		/** The host's foreign system answered. */
		FOREIGN,
		/** The resolver threw; the message is in {@link #error}. */
		ERROR
	}

	/** True when a value is available. */
	public boolean hasValue() {
		return value != null && (status == Status.RESOLVED || status == Status.FOREIGN);
	}

	/** The value, or the fallback when there is none. */
	public String valueOr(String fallback) {
		return hasValue() ? value : fallback;
	}
}
