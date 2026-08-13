package dev.belikhun.luna.legacy.dependency;

import dev.belikhun.luna.legacy.exception.LunaLegacyException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where luna's modules find each other on one server.
 *
 * A feature mod cannot import luna-core's classes directly - each mod is its own
 * jar with its own lifecycle, and on 1.12.2 they share one class loader but not
 * one load order. So the core publishes its services here by interface, and a
 * feature resolves what it needs and degrades when it is absent.
 *
 * A Java 8 counterpart to the modern api's manager, with the same keying
 * (type plus an optional name) and the same rule that resolution never returns
 * null. Two departures, both forced by the language level: the key is a plain
 * final class rather than a record, and lookups return the value or null with
 * an explicit `resolveOptional`-shaped pair of methods, because `Optional` is
 * available but threading it through a Java 8 call site buys nothing here.
 *
 * The factory half of the modern manager is deliberately absent: nothing on this
 * line registers one, and an unused indirection is a thing to maintain rather
 * than a feature.
 */
public final class DependencyManager {
	private final Map<Key, Object> singletons = new ConcurrentHashMap<Key, Object>();

	/** Publish a service under its interface. */
	public <T> void register(Class<T> type, T instance) {
		register(null, type, instance);
	}

	/**
	 * Publish a service under an interface and a name, for the case where two of
	 * the same type coexist.
	 */
	public <T> void register(String name, Class<T> type, T instance) {
		if (type == null) {
			throw new LunaLegacyException("Dependency type không được null.");
		}

		if (instance == null) {
			throw new LunaLegacyException("Dependency instance không được null.");
		}

		singletons.put(new Key(type, normalize(name)), instance);
	}

	/** The service, or null when nothing published one. */
	public <T> T find(Class<T> type) {
		return find(null, type);
	}

	/** The named service, or null when nothing published one. */
	public <T> T find(String name, Class<T> type) {
		if (type == null) {
			throw new LunaLegacyException("Dependency type không được null.");
		}

		Object value = singletons.get(new Key(type, normalize(name)));

		if (value == null) {
			return null;
		}

		if (!type.isInstance(value)) {
			throw new LunaLegacyException("Dependency sai kiểu: " + describe(type, name));
		}

		return type.cast(value);
	}

	/**
	 * The service, or a failure naming what is missing.
	 *
	 * For a caller that genuinely cannot work without it. Anything that can carry
	 * on degraded should use {@link #find} and say so in its log instead.
	 */
	public <T> T require(Class<T> type) {
		T value = find(type);

		if (value == null) {
			throw new LunaLegacyException("Dependency chưa được đăng ký: " + describe(type, null));
		}

		return value;
	}

	public boolean contains(Class<?> type) {
		return singletons.containsKey(new Key(type, ""));
	}

	public void unregister(Class<?> type) {
		singletons.remove(new Key(type, ""));
	}

	public void clear() {
		singletons.clear();
	}

	private static String normalize(String name) {
		return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static String describe(Class<?> type, String name) {
		String normalized = normalize(name);

		return normalized.isEmpty() ? type.getName() : normalized + "::" + type.getName();
	}

	/** A record in the modern api; equals/hashCode written out here. */
	private static final class Key {
		private final Class<?> type;
		private final String name;

		Key(Class<?> type, String name) {
			this.type = type;
			this.name = name;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}

			if (!(other instanceof Key)) {
				return false;
			}

			Key key = (Key) other;

			return type.equals(key.type) && name.equals(key.name);
		}

		@Override
		public int hashCode() {
			return type.hashCode() * 31 + name.hashCode();
		}
	}
}
