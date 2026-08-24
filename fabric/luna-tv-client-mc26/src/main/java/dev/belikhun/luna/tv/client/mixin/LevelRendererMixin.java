package dev.belikhun.luna.tv.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;

import dev.belikhun.luna.tv.client.input.ScreenInput;
import dev.belikhun.luna.tv.client.render.WorldHook;

/**
 * The 26.x render hook.
 *
 * submitEntities is the one place in the frame that hands over all three things
 * a screen needs at once: the pose stack, the collector the geometry goes to, and
 * the world state carrying the camera the frame was extracted with. Injecting at
 * its tail puts the screens in with the entities, which is where they belong -
 * they are entity geometry as far as the renderer is concerned.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "submitEntities", at = @At("TAIL"))
	private void lunatv$submitScreens(
		PoseStack matrices,
		LevelRenderState state,
		SubmitNodeCollector collector,
		CallbackInfo callback
	) {
		WorldHook.render(matrices, state, collector);
	}

	/**
	 * Keeps the block outline off a screen.
	 *
	 * The wall behind a screen is still what the crosshair is on, so the game
	 * draws its selection box over the picture. Pointing at a web page is not
	 * selecting a block. The 1.21 sibling says the same thing through Fabric's
	 * BEFORE_BLOCK_OUTLINE event, which this line does not have.
	 */
	@Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
	private void lunatv$hideOutline(
		PoseStack matrices,
		SubmitNodeCollector collector,
		LevelRenderState state,
		CallbackInfo callback
	) {
		if (ScreenInput.aiming()) {
			callback.cancel();
		}
	}
}
