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

	/**
	 * Draws one translucent ring on a screen's surface.
	 *
	 * Radii are fractions of the picture, one per axis, so the caller can keep
	 * a ring circular in the world on a wall of any shape. An inner radius of
	 * zero makes a filled disc. Like a mark, it floats just above the picture
	 * and blends without writing depth.
	 *
	 * @param quad where the screen is, in world coordinates
	 * @param u the centre, 0 to 1 across the picture
	 * @param v the centre, 0 to 1 down the picture
	 * @param outerU the outer radius, as a fraction of the width
	 * @param outerV the outer radius, as a fraction of the height
	 * @param innerU the inner radius, as a fraction of the width
	 * @param innerV the inner radius, as a fraction of the height
	 * @param argb the ring's colour, alpha included
	 */
	void ring(ScreenQuad quad, double u, double v, double outerU, double outerV,
		double innerU, double innerV, int argb);
}
