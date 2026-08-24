package dev.belikhun.luna.tv.client.render;

import java.nio.ByteBuffer;
import java.util.Locale;

import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * One screen's picture, as a texture Minecraft knows about.
 *
 * Registering it with the texture manager rather than keeping a raw GL name is
 * what lets the wall be drawn through Minecraft's own render pipeline, which in
 * turn is what makes it sit in the world properly: the game applies the view
 * transform, the depth buffer, and whatever a shader pack wants to do with
 * entity geometry. A texture the game has never heard of cannot be handed to a
 * render type.
 */
public final class ScreenTexture implements AutoCloseable {

	private final ResourceLocation location;

	private DynamicTexture texture;
	private int width;
	private int height;

	public ScreenTexture(String screen) {
		this.location = ResourceLocation.fromNamespaceAndPath("lunatvclient",
			"screen/" + screen.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
	}

	/** Where Minecraft knows this texture by. */
	public ResourceLocation location() {
		return location;
	}

	public boolean ready() {
		return texture != null;
	}

	/**
	 * Copies a decoded frame in and uploads it.
	 *
	 * @param rgba tightly packed RGBA bytes, off-heap
	 * @param frameWidth the frame's width
	 * @param frameHeight the frame's height
	 */
	public void upload(ByteBuffer rgba, int frameWidth, int frameHeight) {
		if (texture == null || width != frameWidth || height != frameHeight) {
			recreate(frameWidth, frameHeight);
		}

		NativeImage image = texture.getPixels();

		if (image == null) {
			return;
		}

		// straight into the image's own memory; going through setPixel would be a
		// bounds check and a shift per pixel, several million times a second
		MemoryUtil.memCopy(MemoryUtil.memAddress(rgba), image.getPointer(),
			(long) frameWidth * frameHeight * 4L);
		texture.upload();
	}

	private void recreate(int frameWidth, int frameHeight) {
		close();

		texture = new DynamicTexture(location::toString, frameWidth, frameHeight, false);
		width = frameWidth;
		height = frameHeight;
		Minecraft.getInstance().getTextureManager().register(location, texture);
	}

	@Override
	public void close() {
		if (texture == null) {
			return;
		}

		Minecraft.getInstance().getTextureManager().release(location);
		texture.close();
		texture = null;
	}
}
