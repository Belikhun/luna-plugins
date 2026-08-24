package dev.belikhun.luna.tv.client.screen;

/**
 * A screen's picture arriving from the server, however it is encoded.
 *
 * Two implementations, chosen per connection rather than per build: H.264 when
 * the server can encode it and this machine can decode it, and the older stream
 * of whole JPEGs when either end cannot. The renderer is told neither and asks
 * only for the newest frame.
 */
public interface VideoFeed extends AutoCloseable {

	/** Starts fetching. */
	void start();

	/**
	 * Takes the newest frame, if one arrived since the last call.
	 *
	 * @return the frame, which the caller then owns and must free, or null
	 */
	Frame poll();

	/** Why the feed is unhappy, or null. */
	String failure();

	/** Frames fully decoded since the feed started. */
	long framesDecoded();

	/** Frames decoded but replaced before the renderer took them. */
	long framesDropped();

	/** Compressed bytes taken off the network since the feed started. */
	long bytesReceived();

	/**
	 * Milliseconds spent blocked waiting for network bytes, cumulative.
	 *
	 * Near a thousand per second means the feed is starved: the server or the
	 * link is the limiter, not this machine. Near zero means data floods in
	 * faster than it is asked for.
	 */
	long readStallMillis();

	/**
	 * Milliseconds spent stuck on the decoder, cumulative.
	 *
	 * For H.264 this is time blocked writing into the decoder process: the
	 * pipe only backs up when ffmpeg cannot swallow input at arrival rate, so
	 * a sustained value here is a saturated decoder. For MJPEG it is the time
	 * spent inside the JPEG decode itself, which answers the same question.
	 */
	long decodeStallMillis();

	/** How this feed describes itself in a log line. */
	String codec();

	@Override
	void close();
}
