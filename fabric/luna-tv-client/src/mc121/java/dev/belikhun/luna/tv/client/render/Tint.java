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
