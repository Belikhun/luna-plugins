package dev.belikhun.luna.legacy.placeholder;

import java.util.List;
import java.util.Objects;

/** An identifier split into the providers that may answer it and its parts. */
public final class PlaceholderRoute<T> {
	private final List<T> providers;
	private final String rawNamespace;
	private final String normalizedNamespace;
	private final String rawParams;
	private final String normalizedParams;
	private final boolean safeVariant;

	public PlaceholderRoute(List<T> providers, String rawNamespace, String normalizedNamespace, String rawParams, String normalizedParams, boolean safeVariant) {
		this.providers = providers;
		this.rawNamespace = rawNamespace;
		this.normalizedNamespace = normalizedNamespace;
		this.rawParams = rawParams;
		this.normalizedParams = normalizedParams;
		this.safeVariant = safeVariant;
	}

	public List<T> providers() {
		return providers;
	}

	public String rawNamespace() {
		return rawNamespace;
	}

	public String normalizedNamespace() {
		return normalizedNamespace;
	}

	public String rawParams() {
		return rawParams;
	}

	public String normalizedParams() {
		return normalizedParams;
	}

	public boolean safeVariant() {
		return safeVariant;
	}

	@Override
	public boolean equals(Object value) {
		if (this == value) {
			return true;
		}

		if (!(value instanceof PlaceholderRoute)) {
			return false;
		}

		PlaceholderRoute<?> other = (PlaceholderRoute<?>) value;

		return Objects.equals(providers, other.providers)
			&& Objects.equals(rawNamespace, other.rawNamespace)
			&& Objects.equals(normalizedNamespace, other.normalizedNamespace)
			&& Objects.equals(rawParams, other.rawParams)
			&& Objects.equals(normalizedParams, other.normalizedParams)
			&& safeVariant == other.safeVariant;
	}

	@Override
	public int hashCode() {
		return Objects.hash(providers, rawNamespace, normalizedNamespace, rawParams, normalizedParams, safeVariant);
	}

	@Override
	public String toString() {
		return "PlaceholderRoute[" + "providers=" + providers + ", " + "rawNamespace=" + rawNamespace + ", " + "normalizedNamespace=" + normalizedNamespace + ", " + "rawParams=" + rawParams + ", " + "normalizedParams=" + normalizedParams + ", " + "safeVariant=" + safeVariant + "]";
	}

}
