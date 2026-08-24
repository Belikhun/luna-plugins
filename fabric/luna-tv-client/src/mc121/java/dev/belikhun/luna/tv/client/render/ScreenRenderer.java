package dev.belikhun.luna.tv.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Submits one screen's quad to Minecraft.
 *
 * There is no shader and no OpenGL here on purpose: the picture is geometry as
 * far as the game is concerned, so it is transformed, depth-tested, fogged and
 * handed to a shader pack like anything else, and none of that is reproduced by
 * hand. What it is not is lit. See RenderTypeCompat for why.
 */
public final class ScreenRenderer implements ScreenSink {

	/**
	 * How far the picture floats off the wall it hangs on.
	 *
	 * A screen can sit a hundred thousand blocks from the origin, where a quad
	 * flush against its wall is decided by whichever rounding wins that frame.
	 */
	private static final float SURFACE_OFFSET = 0.01f;

	/** Marks float a little higher still, so they always sit above the picture. */
	private static final float MARK_OFFSET = 0.02f;

	private final PoseStack matrices;
	private final MultiBufferSource consumers;
	private final double cameraX;
	private final double cameraY;
	private final double cameraZ;

	public ScreenRenderer(
		PoseStack matrices,
		MultiBufferSource consumers,
		double cameraX,
		double cameraY,
		double cameraZ
	) {
		this.matrices = matrices;
		this.consumers = consumers;
		this.cameraX = cameraX;
		this.cameraY = cameraY;
		this.cameraZ = cameraZ;
	}

	/**
	 * Draws one screen.
	 *
	 * @param texture the picture
	 * @param quad where the screen is, in world coordinates
	 * @param brightness the operator's brightness percentage
	 * @param glow the operator's glow percentage
	 */
	@Override
	public void draw(ScreenTexture texture, ScreenQuad quad, int brightness, int glow) {
		if (!texture.ready()) {
			return;
		}

		RenderType type = RenderTypeCompat.unlit(texture.location());

		if (type == null) {
			return;
		}

		int colour = Tint.of(brightness, glow);

		matrices.pushPose();
		matrices.translate(-cameraX, -cameraY, -cameraZ);

		VertexConsumer buffer = consumers.getBuffer(type);
		PoseStack.Pose pose = matrices.last();

		// Both windings, because this render type culls and which way round a
		// wall's corners came out is not worth being a bug.
		Tint.quad(buffer, pose, quad, colour, SURFACE_OFFSET, false);
		Tint.quad(buffer, pose, quad, colour, SURFACE_OFFSET, true);

		matrices.popPose();
	}

	/**
	 * Draws one interaction mark on a screen's surface.
	 *
	 * @param quad where the screen is, in world coordinates
	 * @param u0 the left edge, 0 to 1 across the picture
	 * @param v0 the top edge, 0 to 1 down the picture
	 * @param u1 the right edge
	 * @param v1 the bottom edge
	 * @param argb the mark's colour, alpha included
	 */
	@Override
	public void mark(ScreenQuad quad, double u0, double v0, double u1, double v1, int argb) {
		RenderType type = RenderTypeCompat.translucent(ScreenTexture.white());

		if (type == null) {
			return;
		}

		matrices.pushPose();
		matrices.translate(-cameraX, -cameraY, -cameraZ);

		VertexConsumer buffer = consumers.getBuffer(type);
		PoseStack.Pose pose = matrices.last();

		Tint.mark(buffer, pose, quad, u0, v0, u1, v1, argb, MARK_OFFSET, false);
		Tint.mark(buffer, pose, quad, u0, v0, u1, v1, argb, MARK_OFFSET, true);

		matrices.popPose();
	}

	/**
	 * Draws one interaction ring on a screen's surface.
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
	@Override
	public void ring(ScreenQuad quad, double u, double v, double outerU, double outerV,
		double innerU, double innerV, int argb) {
		RenderType type = RenderTypeCompat.translucent(ScreenTexture.white());

		if (type == null) {
			return;
		}

		matrices.pushPose();
		matrices.translate(-cameraX, -cameraY, -cameraZ);

		VertexConsumer buffer = consumers.getBuffer(type);
		PoseStack.Pose pose = matrices.last();

		Tint.ring(buffer, pose, quad, u, v, outerU, outerV, innerU, innerV,
			argb, MARK_OFFSET, false);
		Tint.ring(buffer, pose, quad, u, v, outerU, outerV, innerU, innerV,
			argb, MARK_OFFSET, true);

		matrices.popPose();
	}
}
