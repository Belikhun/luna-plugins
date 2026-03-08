package dev.belikhun.luna.core.api.dependency;

import dev.belikhun.luna.core.api.exception.DependencyException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class DependencyManager {
	private final Map<Key, Provider<?>> providers;

	public DependencyManager() {
		this.providers = new ConcurrentHashMap<>();
	}

	public <T> void registerSingleton(Class<T> type, T instance) {
		registerSingleton(null, type, instance);
	}

	public <T> void registerSingleton(String name, Class<T> type, T instance) {
		if (type == null) {
			throw new DependencyException("Dependency type không được null.");
		}
		if (instance == null) {
			throw new DependencyException("Dependency instance không được null.");
		}
		Key key = new Key(type, normalizeName(name));
		providers.put(key, Provider.singleton(instance));
	}

	public <T> void registerFactory(Class<T> type, Supplier<? extends T> factory) {
		registerFactory(null, type, factory, true);
	}

	public <T> void registerFactory(String name, Class<T> type, Supplier<? extends T> factory, boolean reuseSingleton) {
		if (type == null) {
			throw new DependencyException("Dependency type không được null.");
		}
		if (factory == null) {
			throw new DependencyException("Dependency factory không được null.");
		}
		Key key = new Key(type, normalizeName(name));
		providers.put(key, Provider.factory(factory, reuseSingleton));
	}

	public <T> T resolve(Class<T> type) {
		return resolve(null, type);
	}

	public <T> T resolve(String name, Class<T> type) {
		return resolveOptional(name, type)
			.orElseThrow(() -> new DependencyException("Dependency is not registered: " + describe(type, name)));
	}

	public <T> Optional<T> resolveOptional(Class<T> type) {
		return resolveOptional(null, type);
	}

	public <T> Optional<T> resolveOptional(String name, Class<T> type) {
		if (type == null) {
			throw new DependencyException("Dependency type không được null.");
		}
		Key key = new Key(type, normalizeName(name));
		Provider<?> provider = providers.get(key);
		if (provider == null) {
			return Optional.empty();
		}

		Object value = provider.get();
		if (value == null) {
			return Optional.empty();
		}

		if (!type.isInstance(value)) {
			throw new DependencyException("Dependency type mismatch for " + describe(type, name));
		}

		return Optional.of(type.cast(value));
	}

	public <T> boolean contains(Class<T> type) {
		return contains(null, type);
	}

	public <T> boolean contains(String name, Class<T> type) {
		return providers.containsKey(new Key(type, normalizeName(name)));
	}

	public <T> void unregister(Class<T> type) {
		unregister(null, type);
	}

	public <T> void unregister(String name, Class<T> type) {
		providers.remove(new Key(type, normalizeName(name)));
	}

	public void clear() {
		providers.clear();
	}

	private String normalizeName(String name) {
		return name == null ? "" : name.trim().toLowerCase();
	}

	private String describe(Class<?> type, String name) {
		String normalized = normalizeName(name);
		if (normalized.isEmpty()) {
			return type.getName();
		}

		return normalized + "::" + type.getName();
	}

	private record Key(Class<?> type, String name) {
	}

	private static final class Provider<T> {
		private final Supplier<? extends T> supplier;
		private final boolean singleton;
		private volatile boolean initialized;
		private T cached;

		private Provider(Supplier<? extends T> supplier, boolean singleton) {
			this.supplier = supplier;
			this.singleton = singleton;
			this.initialized = false;
		}

		static <T> Provider<T> singleton(T instance) {
			return new Provider<>(() -> instance, true);
		}

		static <T> Provider<T> factory(Supplier<? extends T> supplier, boolean singleton) {
			return new Provider<>(supplier, singleton);
		}

		T get() {
			if (!singleton) {
				return supplier.get();
			}

			if (!initialized) {
				synchronized (this) {
					if (!initialized) {
						cached = supplier.get();
						initialized = true;
					}
				}
			}

			return cached;
		}
	}
}

