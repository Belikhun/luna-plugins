package dev.belikhun.luna.tv.audio;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded ring of 20ms audio frames, one producer and one consumer.
 *
 * Overflow drops the oldest frame rather than blocking the producer or growing:
 * the producer is a pipe from parec, and a consumer that fell behind wants the
 * present, not a backlog that would turn into permanent latency.
 */
public final class AudioFrameRing {

	private final short[][] slots;
	private final AtomicInteger head = new AtomicInteger();
	private final AtomicInteger tail = new AtomicInteger();

	/**
	 * @param capacity number of frames held; 16 is about 320ms
	 * @param frameSize samples per frame, 960 for voice chat
	 */
	public AudioFrameRing(int capacity, int frameSize) {
		this.slots = new short[capacity][frameSize];
	}

	/**
	 * Publishes a frame, overwriting the oldest when full.
	 *
	 * @param frame exactly {@code frameSize} samples; copied, not retained
	 */
	public void push(short[] frame) {
		int position = head.get();

		System.arraycopy(frame, 0, slots[position % slots.length], 0, frame.length);
		head.set(position + 1);

		int lag = head.get() - tail.get();

		if (lag > slots.length) {
			tail.set(head.get() - slots.length);
		}
	}

	/**
	 * Takes the oldest frame.
	 *
	 * @param into buffer to copy into
	 * @return true when a frame was available
	 */
	public boolean poll(short[] into) {
		int position = tail.get();

		if (position >= head.get()) {
			return false;
		}

		System.arraycopy(slots[position % slots.length], 0, into, 0, into.length);
		tail.set(position + 1);

		return true;
	}

	/** Drops everything buffered, used when a stream restarts. */
	public void clear() {
		tail.set(head.get());
	}
}
