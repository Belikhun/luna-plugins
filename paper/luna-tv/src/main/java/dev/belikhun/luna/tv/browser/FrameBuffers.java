package dev.belikhun.luna.tv.browser;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A tiny recycling pool of ARGB frame arrays.
 *
 * A 640x360 frame is 900KB of ints; at 20fps that is 18MB/s of garbage per
 * screen if every decode allocates. The render thread hands each consumed
 * buffer back, so a steady stream settles on three arrays and stops allocating.
 */
public final class FrameBuffers {

	private static final int POOL_SIZE = 3;

	private final BlockingQueue<int[]> free = new ArrayBlockingQueue<>(POOL_SIZE);
	private final int length;

	/**
	 * @param length pixel count every buffer in this pool holds
	 */
	public FrameBuffers(int length) {
		this.length = length;
	}

	/**
	 * Takes a buffer, allocating only when the pool is dry.
	 *
	 * @return an array of the pool's length, contents undefined
	 */
	public int[] take() {
		int[] buffer = free.poll();

		if (buffer != null && buffer.length == length) {
			return buffer;
		}

		return new int[length];
	}

	/**
	 * Returns a buffer for reuse. A buffer of the wrong size (the screen was
	 * resized) is dropped rather than poisoning the pool.
	 *
	 * @param buffer the array the consumer is finished with
	 */
	public void recycle(int[] buffer) {
		if (buffer == null || buffer.length != length) {
			return;
		}

		free.offer(buffer);
	}
}
