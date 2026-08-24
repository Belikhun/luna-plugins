package dev.belikhun.luna.tv.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * The per-frame callback, driven by a mixin rather than an event.
 *
 * Fabric API has no world render events on this game line - not in 26.1, not in
 * 26.2, not in the newest build of either - so unlike the 1.21 sibling there is
 * nothing to subscribe to. The mixin on LevelRenderer.submitEntities calls in
 * here instead, which is the same place in the frame the sibling's AFTER_ENTITIES
 * lands, and hands over the same three things: the pose stack, the collector, and
 * the camera the frame was extracted with.
 */
public final class WorldHook {

	/** What the mod wants to do each frame. */
	public interface Frame {

		/**
		 * Draws the screens.
		 *
		 * @param sink where this frame's geometry goes
		 */
		void draw(ScreenSink sink);
	}

	private static volatile Frame frame;

	private WorldHook() {
	}

	/**
	 * Registers the callback the mixin will drive.
	 *
	 * @param callback what to draw
	 */
	public static void install(Frame callback) {
		frame = callback;
	}

	/**
	 * Called from the mixin, once per frame.
	 *
	 * @param matrices the frame's pose stack
	 * @param state the frame's extracted world state
	 * @param collector where the geometry goes
	 */
	public static void render(PoseStack matrices, LevelRenderState state, SubmitNodeCollector collector) {
		Frame callback = frame;

		if (callback == null || state == null) {
			return;
		}

		CameraRenderState camera = state.cameraRenderState;

		if (camera == null || !camera.initialized || camera.pos == null) {
			return;
		}

		callback.draw(new ScreenRenderer(matrices, collector,
			camera.pos.x, camera.pos.y, camera.pos.z));
	}
}
