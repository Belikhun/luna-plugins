package dev.belikhun.luna.core.api.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FutureUtils {
	private FutureUtils() {
	}

	public static <T> T await(CompletableFuture<T> future, long timeoutMillis, T fallback, boolean nonBlocking) {
		if (future == null) {
			return fallback;
		}

		if (nonBlocking) {
			return future.isDone() ? future.getNow(fallback) : fallback;
		}

		try {
			long safeTimeout = Math.max(1L, timeoutMillis);
			return future.get(safeTimeout, TimeUnit.MILLISECONDS);
		} catch (Exception exception) {
			return fallback;
		}
	}
}
