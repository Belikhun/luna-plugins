package dev.belikhun.luna.core.api.config;

public final class ConfigNode {
	private final ConfigStore store;
	private final String path;

	ConfigNode(ConfigStore store, String path) {
		this.store = store;
		this.path = path;
	}

	public ConfigNode get(String childPath) {
		String normalized = store.normalize(childPath);
		if (path.isEmpty()) {
			return new ConfigNode(store, normalized);
		}

		if (normalized.isEmpty()) {
			return this;
		}

		return new ConfigNode(store, path + "." + normalized);
	}

	public Object value() {
		return path.isEmpty() ? store.raw() : store.raw().get(path);
	}

	public <T> T value(Class<T> type, T fallback) {
		Object rawValue = value();
		if (rawValue == null || !type.isInstance(rawValue)) {
			return fallback;
		}

		return type.cast(rawValue);
	}

	public String asString(String fallback) {
		Object rawValue = value();
		return rawValue == null ? fallback : String.valueOf(rawValue);
	}

	public int asInt(int fallback) {
		Object rawValue = value();
		if (rawValue instanceof Number number) {
			return number.intValue();
		}

		try {
			return rawValue == null ? fallback : Integer.parseInt(rawValue.toString());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public long asLong(long fallback) {
		Object rawValue = value();
		if (rawValue instanceof Number number) {
			return number.longValue();
		}

		try {
			return rawValue == null ? fallback : Long.parseLong(rawValue.toString());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public double asDouble(double fallback) {
		Object rawValue = value();
		if (rawValue instanceof Number number) {
			return number.doubleValue();
		}

		try {
			return rawValue == null ? fallback : Double.parseDouble(rawValue.toString());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public boolean asBoolean(boolean fallback) {
		Object rawValue = value();
		if (rawValue instanceof Boolean bool) {
			return bool;
		}

		if (rawValue == null) {
			return fallback;
		}

		return Boolean.parseBoolean(rawValue.toString());
	}

	public ConfigNode set(Object value) {
		store.raw().set(path, value);
		return this;
	}

	public ConfigNode remove() {
		store.raw().set(path, null);
		return this;
	}

	public String path() {
		return path;
	}
}

