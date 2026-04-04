package dev.belikhun.luna.core.api.cache;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ExpiringCache<K, V> {
	private final long ttlMillis;
	private final ConcurrentMap<K, Entry<V>> values;

	public ExpiringCache(long ttlMillis) {
		this.ttlMillis = Math.max(1L, ttlMillis);
		this.values = new ConcurrentHashMap<>();
	}

	public void put(K key, V value) {
		if (key == null) {
			return;
		}

		values.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
	}

	public Optional<V> get(K key) {
		if (key == null) {
			return Optional.empty();
		}

		Entry<V> entry = values.get(key);
		if (entry == null) {
			return Optional.empty();
		}

		if (entry.expiresAtEpochMillis() < System.currentTimeMillis()) {
			values.remove(key, entry);
			return Optional.empty();
		}

		return Optional.ofNullable(entry.value());
	}

	public void remove(K key) {
		if (key != null) {
			values.remove(key);
		}
	}

	public void clear() {
		values.clear();
	}

	private record Entry<V>(V value, long expiresAtEpochMillis) {
	}
}
