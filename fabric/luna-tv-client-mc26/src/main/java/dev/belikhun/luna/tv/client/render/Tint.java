package dev.belikhun.luna.tv.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * The vertices of a screen, and how bright they are.
 *
 * Written once per compat set rather than shared, since the vertex consumer is
 * one of the few classes both game lines still agree on. The render type is
 * unlit, so the operator's brightness and glow can only be spent as a vertex
 * colour: white is the page's own colours, and anything less dims it. Neither
 * can push past white, which is the honest limit of not writing a shader.
 */
final class Tint {

	/**
	 * Full block light and full sky light, packed.
	 *
	 * The literal rather than LightTexture.FULL_BRIGHT because the two game lines
	 * keep that constant in differently named classes, and this file is otherwise
	 * identical in both.
	 */
	private static final int FULL_BRIGHT = 0xF000F0;

	private Tint() {
	}

	/** Packs the two operator percentages into one white-or-dimmer colour. */
	static int of(int brightness, int glow) {
		float scale = Math.min(1.0f, Math.max(0.0f, brightness / 100.0f))
			* Math.min(1.0f, Math.max(0.0f, glow / 100.0f));
		int level = Math.round(255.0f * scale);

		return 0xFF000000 | (level << 16) | (level << 8) | level;
	}

	/**
	 * Writes one face of a screen.
	 *
	 * @param reversed the back face, wound the other way
	 */
	static void quad(
		VertexConsumer buffer,
		PoseStack.Pose pose,
		ScreenQuad quad,
		int colour,
		float lift,
		boolean reversed
	) {
		float normalX = (float) (reversed ? -quad.normalX() : quad.normalX());
		float normalY = (float) (reversed ? -quad.normalY() : quad.normalY());
		float normalZ = (float) (reversed ? -quad.normalZ() : quad.normalZ());

		if (reversed) {
			corner(buffer, pose, quad, 1, 1.0f, 0.0f, colour, lift, normalX, normalY, normalZ);
			corner(buffer, pose, quad, 2, 1.0f, 1.0f, colour, lift, normalX, normalY, normalZ);
			corner(buffer, pose, quad, 3, 0.0f, 1.0f, colour, lift, normalX, normalY, normalZ);
			corner(buffer, pose, quad, 0, 0.0f, 0.0f, colour, lift, normalX, normalY, normalZ);

			return;
		}

		corner(buffer, pose, quad, 0, 0.0f, 0.0f, colour, lift, normalX, normalY, normalZ);
		corner(buffer, pose, quad, 3, 0.0f, 1.0f, colour, lift, normalX, normalY, normalZ);
		corner(buffer, pose, quad, 2, 1.0f, 1.0f, colour, lift, normalX, normalY, normalZ);
		corner(buffer, pose, quad, 1, 1.0f, 0.0f, colour, lift, normalX, normalY, normalZ);
	}

	/**
	 * Writes one overlay rectangle on the screen's plane.
	 *
	 * The corners come from the quad's own interpolation, so the rectangle
	 * follows whichever way the wall faces, and the colour carries the alpha:
	 * the caller pairs this with the translucent render type.
	 *
	 * @param u0 the left edge, 0 to 1 across the picture
	 * @param v0 the top edge, 0 to 1 down the picture
	 * @param u1 the right edge
	 * @param v1 the bottom edge
	 * @param reversed the back face, wound the other way
	 */
	static void mark(
		VertexConsumer buffer,
		PoseStack.Pose pose,
		ScreenQuad quad,
		double u0,
		double v0,
		double u1,
		double v1,
		int colour,
		float lift,
		boolean reversed
	) {
		float normalX = (float) (reversed ? -quad.normalX() : quad.normalX());
		float normalY = (float) (reversed ? -quad.normalY() : quad.normalY());
		float normalZ = (float) (reversed ? -quad.normalZ() : quad.normalZ());

		if (reversed) {
			point(buffer, pose, quad, u1, v0, colour, lift, normalX, normalY, normalZ);
			point(buffer, pose, quad, u1, v1, colour, lift, normalX, normalY, normalZ);
			point(buffer, pose, quad, u0, v1, colour, lift, normalX, normalY, normalZ);
			point(buffer, pose, quad, u0, v0, colour, lift, normalX, normalY, normalZ);

			return;
		}

		point(buffer, pose, quad, u0, v0, colour, lift, normalX, normalY, normalZ);
		point(buffer, pose, quad, u0, v1, colour, lift, normalX, normalY, normalZ);
		point(buffer, pose, quad, u1, v1, colour, lift, normalX, normalY, normalZ);
		point(buffer, pose, quad, u1, v0, colour, lift, normalX, normalY, normalZ);
	}

	/** Segments per ring; at cursor sizes, twenty-four already reads as round. */
	private static final int RING_SEGMENTS = 24;

	/**
	 * Writes one overlay ring on the screen's plane.
	 *
	 * Radii are fractions of the picture, one per axis, so a caller working in
	 * blocks can keep the ring circular in the world. An inner radius of zero
	 * makes a filled disc: the inner points collapse into the centre and each
	 * segment degenerates into a triangle, which draws fine. Every vertex is
	 * clamped onto the picture, so a ring spilling over the wall's edge is cut
	 * off there rather than floating in the air beside it.
	 *
	 * @param u the centre, 0 to 1 across the picture
	 * @param v the centre, 0 to 1 down the picture
	 * @param outerU the outer radius, as a fraction of the width
	 * @param outerV the outer radius, as a fraction of the height
	 * @param innerU the inner radius, as a fraction of the width
	 * @param innerV the inner radius, as a fraction of the height
	 * @param reversed the back face, wound the other way
	 */
	static void ring(
		VertexConsumer buffer,
		PoseStack.Pose pose,
		ScreenQuad quad,
		double u,
		double v,
		double outerU,
		double outerV,
		double innerU,
		double innerV,
		int colour,
		float lift,
		boolean reversed
	) {
		float normalX = (float) (reversed ? -quad.normalX() : quad.normalX());
		float normalY = (float) (reversed ? -quad.normalY() : quad.normalY());
		float normalZ = (float) (reversed ? -quad.normalZ() : quad.normalZ());

		for (int segment = 0; segment < RING_SEGMENTS; segment++) {
			double from = Math.PI * 2.0 * segment / RING_SEGMENTS;
			double to = Math.PI * 2.0 * (segment + 1) / RING_SEGMENTS;
			double cosFrom = Math.cos(from);
			double sinFrom = Math.sin(from);
			double cosTo = Math.cos(to);
			double sinTo = Math.sin(to);

			double outerFromU = clamp(u + cosFrom * outerU);
			double outerFromV = clamp(v + sinFrom * outerV);
			double outerToU = clamp(u + cosTo * outerU);
			double outerToV = clamp(v + sinTo * outerV);
			double innerFromU = clamp(u + cosFrom * innerU);
			double innerFromV = clamp(v + sinFrom * innerV);
			double innerToU = clamp(u + cosTo * innerU);
			double innerToV = clamp(v + sinTo * innerV);

			if (reversed) {
				point(buffer, pose, quad, outerToU, outerToV, colour, lift, normalX, normalY, normalZ);
				point(buffer, pose, quad, innerToU, innerToV, colour, lift, normalX, normalY, normalZ);
				point(buffer, pose, quad, innerFromU, innerFromV, colour, lift, normalX, normalY, normalZ);
				point(buffer, pose, quad, outerFromU, outerFromV, colour, lift, normalX, normalY, normalZ);

				continue;
			}

			point(buffer, pose, quad, outerFromU, outerFromV, colour, lift, normalX, normalY, normalZ);
			point(buffer, pose, quad, innerFromU, innerFromV, colour, lift, normalX, normalY, normalZ);
			point(buffer, pose, quad, innerToU, innerToV, colour, lift, normalX, normalY, normalZ);
			point(buffer, pose, quad, outerToU, outerToV, colour, lift, normalX, normalY, normalZ);
		}
	}

	private static double clamp(double fraction) {
		return Math.max(0.0, Math.min(1.0, fraction));
	}

	private static void point(
		VertexConsumer buffer,
		PoseStack.Pose pose,
		ScreenQuad quad,
		double u,
		double v,
		int colour,
		float lift,
		float normalX,
		float normalY,
		float normalZ
	) {
		float x = (float) (quad.at(u, v, 0) + quad.normalX() * lift);
		float y = (float) (quad.at(u, v, 1) + quad.normalY() * lift);
		float z = (float) (quad.at(u, v, 2) + quad.normalZ() * lift);

		// the texture is a single white pixel, so any coordinate samples it;
		// the picture's own fractions keep the numbers meaningful in a capture
		buffer.addVertex(pose, x, y, z)
			.setColor(colour)
			.setUv((float) u, (float) v)
			.setLight(FULL_BRIGHT)
			.setNormal(pose, normalX, normalY, normalZ);
	}

	private static void corner(
		VertexConsumer buffer,
		PoseStack.Pose pose,
		ScreenQuad quad,
		int index,
		float u,
		float v,
		int colour,
		float lift,
		float normalX,
		float normalY,
		float normalZ
	) {
		float x = (float) (quad.corner(index, 0) + quad.normalX() * lift);
		float y = (float) (quad.corner(index, 1) + quad.normalY() * lift);
		float z = (float) (quad.corner(index, 2) + quad.normalZ() * lift);

		// The beacon beam's shader reads none of the light or the normal, but its
		// vertex format still declares them, and a vertex missing an element its
		// format has is refused outright. They are filled in anyway because a
		// shader pack replaces that shader and does read them: full light so Iris
		// does not dim the picture, and the real normal so it faces the right way.
		buffer.addVertex(pose, x, y, z)
			.setColor(colour)
			.setUv(u, v)
			.setLight(FULL_BRIGHT)
			.setNormal(pose, normalX, normalY, normalZ);
	}
}
