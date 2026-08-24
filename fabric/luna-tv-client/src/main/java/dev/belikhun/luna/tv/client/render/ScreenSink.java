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

	/**
	 * Draws one translucent rectangle on a screen's surface.
	 *
	 * Coordinates are the picture's own: 0,0 the top left corner and 1,1 the
	 * bottom right. The rectangle floats just above the picture, so it reads as
	 * a mark on the page rather than part of it, and it blends rather than
	 * writing depth, so overlapping marks never fight.
	 *
	 * @param quad where the screen is, in world coordinates
	 * @param u0 the left edge, 0 to 1 across the picture
	 * @param v0 the top edge, 0 to 1 down the picture
	 * @param u1 the right edge
	 * @param v1 the bottom edge
	 * @param argb the mark's colour, alpha included
	 */
	void mark(ScreenQuad quad, double u0, double v0, double u1, double v1, int argb);
}
