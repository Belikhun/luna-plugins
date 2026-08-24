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
}
