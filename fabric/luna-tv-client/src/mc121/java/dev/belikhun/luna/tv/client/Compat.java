package dev.belikhun.luna.tv.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import dev.belikhun.luna.tv.client.input.HudCursor;
import dev.belikhun.luna.tv.client.input.ScreenInput;
import dev.belikhun.luna.tv.client.net.TvPayload;

/**
 * The handful of calls the two game lines spell differently.
 *
 * Everything else in the shared sources compiles unchanged on both; these are the
 * three that do not, so they live behind one class per line rather than pushing a
 * copy of the whole client into each.
 */
public final class Compat {

	private Compat() {
	}

	/** Declares the three plugin-message channels. */
	public static void registerPayloads() {
		PayloadTypeRegistry.playC2S().register(TvPayload.HELLO, TvPayload.codec(TvPayload.HELLO));
		PayloadTypeRegistry.playC2S().register(TvPayload.INPUT, TvPayload.codec(TvPayload.INPUT));
		PayloadTypeRegistry.playS2C().register(TvPayload.SCREENS, TvPayload.codec(TvPayload.SCREENS));
	}


	/**
	 * Stops the block outline being drawn through a screen.
	 *
	 * The wall behind a screen is still what the crosshair is technically on, so
	 * the game draws its selection box over the picture. Pointing at a web page
	 * is not selecting a block, and the outline says otherwise.
	 */
	public static void installOutline() {
		WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, hit) -> !ScreenInput.aiming());
	}

	/** Puts the marks beside the crosshair, and swaps the crosshair for a caret. */
	public static void installHud() {
		HudElementRegistry.addLast(
			ResourceLocation.fromNamespaceAndPath("lunatvclient", "input_hint"),
			(graphics, tick) -> HudCursor.draw(graphics::fill,
				graphics.guiWidth(), graphics.guiHeight()));

		// Replacing rather than drawing over: two crosshairs on top of each other
		// is worse than either, and the vanilla one has to keep working every
		// other moment of the game.
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, tick) -> {
			if (!HudCursor.keyboardCaptured()) {
				original.render(graphics, tick);

				return;
			}

			HudCursor.caret(graphics::fill, graphics.guiWidth(), graphics.guiHeight());
		});
	}

	/**
	 * The camera's own frame of reference, for placing a screen's sound.
	 *
	 * Twelve plain doubles rather than a type of ours, because every vector class
	 * in this call is named differently on the two game lines while the arithmetic
	 * that consumes them is shared.
	 *
	 * @return position, then the left, up and forward unit vectors, or null when
	 *         there is no camera yet
	 */
	public static double[] listener() {
		Minecraft client = Minecraft.getInstance();
		Camera camera = client.gameRenderer.getMainCamera();

		if (camera == null) {
			return null;
		}

		Vec3 at = camera.position();

		// Built from the camera's own quaternion rather than read off it. The
		// accessors that hand these over directly were renamed between 1.21.10
		// and 1.21.11 - getLookVector became forwardVector, and getPosition was
		// deleted outright - so asking for them by name is a crash on one of the
		// two versions whichever name is chosen. position() and rotation() are
		// spelled the same on every version this mod runs on, and these are the
		// three base vectors Minecraft rotates itself, FORWARDS pointing along
		// -Z the way a camera looks in OpenGL's convention.
		Quaternionf facing = camera.rotation();
		Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f).rotate(facing);
		Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f).rotate(facing);
		Vector3f left = new Vector3f(-1.0f, 0.0f, 0.0f).rotate(facing);

		return new double[] {
			at.x, at.y, at.z,
			left.x(), left.y(), left.z(),
			up.x(), up.y(), up.z(),
			forward.x(), forward.y(), forward.z(),
		};
	}

	/**
	 * How loud the player wants this kind of sound, master volume included.
	 *
	 * A screen is put in the jukebox category, which is the vanilla slider for
	 * recorded sound coming out of a block, and is what somebody looking for a
	 * way to turn the television down will reach for.
	 *
	 * Read from the raw options rather than getFinalSoundSourceVolume on
	 * purpose: the "final" getter is where focus-idling mods apply their duck
	 * when the game window loses focus, and a television is the one sound that
	 * should keep playing while its owner is in another window. The duck those
	 * mods put on the OpenAL listener itself is divided back out where the
	 * source gains are computed.
	 *
	 * @return the multiplier, 0 to 1
	 */
	public static float mediaVolume() {
		var options = Minecraft.getInstance().options;
		double master = options.getSoundSourceOptionInstance(SoundSource.MASTER).get();
		double records = options.getSoundSourceOptionInstance(SoundSource.RECORDS).get();

		return (float) (master * records);
	}

	/** Stops the game's background music; called each tick while a screen sounds. */
	public static void suppressMusic() {
		Minecraft.getInstance().getMusicManager().stopPlaying();
	}

	/**
	 * The dimension the player is in, as the server also names it.
	 *
	 * @return the namespaced key, or empty when there is no world
	 */
	public static String dimensionKey() {
		Minecraft client = Minecraft.getInstance();

		if (client.level == null) {
			return "";
		}

		return client.level.dimension().location().toString();
	}
}
