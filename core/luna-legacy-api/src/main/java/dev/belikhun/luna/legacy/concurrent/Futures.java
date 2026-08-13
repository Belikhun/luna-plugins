package dev.belikhun.luna.legacy.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The `CompletableFuture` factories Java 8 does not have.
 *
 * `failedFuture` arrived in Java 9 and is used wherever a call refuses before it
 * starts - no carrier online, an unsupported action - so it is worth one helper
 * rather than a three-line dance at every site.
 */
public final class Futures {
	private Futures() {
	}

	/** A future already completed with this failure. */
	public static <T> CompletableFuture<T> failed(Throwable failure) {
		CompletableFuture<T> future = new CompletableFuture<T>();

		future.completeExceptionally(failure);

		return future;
	}

	/**
	 * The future's value, or the fallback.
	 *
	 * `nonBlocking` is what the server thread passes: waiting there stalls the
	 * tick, so an unfinished future answers with the fallback instead and the
	 * caller shows a stale number rather than freezing the game.
	 */
	public static <T> T await(CompletableFuture<T> future, long timeoutMillis, T fallback, boolean nonBlocking) {
		if (future == null) {
			return fallback;
		}

		if (nonBlocking) {
			return future.isDone() ? future.getNow(fallback) : fallback;
		}

		try {
			return future.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
		} catch (Exception exception) {
			return fallback;
		}
	}
}
