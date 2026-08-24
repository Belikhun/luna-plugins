package dev.belikhun.luna.tv.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Submits one screen's quad to Minecraft, the 26.x way.
 *
 * The 1.21 sibling hands vertices to a MultiBufferSource. That class does not
 * exist here: 26.x collects geometry through submit nodes and draws it later,
 * which is why the two lines need separate renderers even though the vertices
 * they build are identical.
 *
 * The beacon beam is borrowed for the same reason it is there: its shader samples
 * the texture, multiplies by the vertex colour and applies fog, and does nothing
 * else. An entity type would put the picture through the lightmap and through
 * Minecraft's directional shading, which darkens an east or west facing quad
 * whatever light value is handed in.
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
	private final SubmitNodeCollector collector;
	private final double cameraX;
	private final double cameraY;
	private final double cameraZ;

	public ScreenRenderer(
		PoseStack matrices,
		SubmitNodeCollector collector,
		double cameraX,
		double cameraY,
		double cameraZ
	) {
		this.matrices = matrices;
		this.collector = collector;
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

		RenderType type = RenderTypes.beaconBeam(texture.location(), false);
		int colour = Tint.of(brightness, glow);

		matrices.pushPose();
		matrices.translate(-cameraX, -cameraY, -cameraZ);

		// Both windings, because this render type culls and which way round a
		// wall's corners came out is not worth being a bug.
		collector.submitCustomGeometry(matrices, type,
			(pose, buffer) -> Tint.quad(buffer, pose, quad, colour, SURFACE_OFFSET, false));
		collector.submitCustomGeometry(matrices, type,
			(pose, buffer) -> Tint.quad(buffer, pose, quad, colour, SURFACE_OFFSET, true));

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
		// true is the beacon's "render through": translucent, colour-write
		// only, so overlapping marks blend in draw order instead of z-fighting
		RenderType type = RenderTypes.beaconBeam(ScreenTexture.white(), true);

		matrices.pushPose();
		matrices.translate(-cameraX, -cameraY, -cameraZ);

		collector.submitCustomGeometry(matrices, type, (pose, buffer) -> {
			Tint.mark(buffer, pose, quad, u0, v0, u1, v1, argb, MARK_OFFSET, false);
			Tint.mark(buffer, pose, quad, u0, v0, u1, v1, argb, MARK_OFFSET, true);
		});

		matrices.popPose();
	}
}
