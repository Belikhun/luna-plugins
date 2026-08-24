package dev.belikhun.luna.tv.client.render;

/**
 * Where a screen's quad goes, once the game is ready to be given geometry.
 *
 * The whole reason this is an interface is that the two game lines hand geometry
 * over differently: 1.21.x takes a MultiBufferSource, and 26.x has no such class
 * at all, having replaced it with a submit-node collector. Everything above this
 * line - which screens exist, which one is in this dimension, which frame is
 * newest - is identical on both, so it is written once and the sink is what
 * differs.
 */
public interface ScreenSink {

	/**
	 * Draws one screen where it stands.
	 *
	 * @param texture the picture
	 * @param quad where the screen is, in world coordinates
	 * @param brightness the operator's brightness percentage
	 * @param glow the operator's glow percentage
	 */
	void draw(ScreenTexture texture, ScreenQuad quad, int brightness, int glow);
}
