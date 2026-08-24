package dev.belikhun.luna.tv.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

import net.minecraft.client.renderer.state.CameraRenderState;

/**
 * The per-frame callback, kept in a class of its own.
 *
 * Isolating the WorldRenderEvents reference here is deliberate. Fabric deleted
 * the world render events in the 1.21.9 port and reinstated them under a new
 * package in 1.21.10, so on a client outside that range the class this needs is
 * absent and touching it throws NoClassDefFoundError. A separate class means the
 * failure lands when this one is loaded, where the caller can catch it, rather
 * than part-way through the mod's initialiser, where it takes the game down.
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

	private WorldHook() {
	}

	/**
	 * Subscribes to the world render event.
	 *
	 * AFTER_ENTITIES, and the geometry goes through the game's own buffers rather
	 * than straight to OpenGL. Drawing it by hand was the mistake underneath a
	 * whole run of symptoms: the picture had to be given a view matrix, and every
	 * attempt to reproduce that matrix was wrong in some way the world was not.
	 * Reconstructing it from the camera left out view bobbing, which lives on the
	 * pose stack, so the wall swayed while the picture stood still; taking the
	 * pose stack alone left out the view rotation, which does not, so the picture
	 * stuck to the screen. Submitting to the game means never holding that matrix
	 * at all.
	 *
	 * The camera position still comes from the frame's render state, because
	 * since 1.21.9 that is what LevelRenderer positions everything else against.
	 *
	 * @param frame what to draw
	 * @throws LinkageError when this Minecraft version has no such event
	 */
	public static void install(Frame frame) {
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			CameraRenderState camera = context.worldState().cameraRenderState;

			if (camera == null || !camera.initialized || camera.pos == null) {
				return;
			}

			frame.draw(new ScreenRenderer(context.matrices(), context.consumers(),
				camera.pos.x, camera.pos.y, camera.pos.z));
		});
	}
}
