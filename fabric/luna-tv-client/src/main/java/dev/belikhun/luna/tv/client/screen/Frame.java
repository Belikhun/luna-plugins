package dev.belikhun.luna.tv.client.screen;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.lwjgl.system.MemoryUtil;

/**
 * One decoded picture, ready for the texture upload.
 *
 * The pixels live outside the heap because that is where the upload wants them,
 * and a frame is a few megabytes that would otherwise be collected twenty or
 * more times a second. Whoever takes a frame owns it and must free it; the feed
 * frees any frame that is replaced before anybody drew it.
 */
public final class Frame {

	private final ByteBuffer rgba;
	private final int width;
	private final int height;
	private final long pts;

	/**
	 * When the decode finished, on this machine's monotonic clock.
	 *
	 * A frame is built the moment its pixels are ready, so construction time is
	 * decode-out time; the renderer subtracts it at upload to measure how long
	 * finished pictures sit waiting to be drawn.
	 */
	private final long bornNanos = System.nanoTime();

	/** What freeing means: back to the pool, or back to the allocator. */
	private final Consumer<ByteBuffer> release;

	Frame(ByteBuffer rgba, int width, int height, long pts) {
		this(rgba, width, height, pts, MemoryUtil::memFree);
	}

	Frame(ByteBuffer rgba, int width, int height, long pts, Consumer<ByteBuffer> release) {
		this.rgba = rgba;
		this.width = width;
		this.height = height;
		this.pts = pts;
		this.release = release;
	}

	public ByteBuffer rgba() {
		return rgba;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** When the server captured it, in microseconds on its own clock. */
	public long pts() {
		return pts;
	}

	/** When the decode finished, in nanoTime on this machine. */
	public long bornNanos() {
		return bornNanos;
	}

	/**
	 * Releases the pixels. Called once, by whoever took the frame.
	 *
	 * A feed that always produces the same size hands them back to its own pool
	 * instead: at this resolution a frame is six megabytes, and allocating one
	 * per frame means faulting in fifteen hundred fresh pages sixty times a
	 * second for pixels that are about to be overwritten anyway.
	 */
	public void free() {
		release.accept(rgba);
	}
}
