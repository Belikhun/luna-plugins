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

	/** How this feed describes itself in a log line. */
	String codec();

	@Override
	void close();
}
