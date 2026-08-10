package dev.belikhun.luna.core.mc.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;

/**
 * An item to and from a tag through {@code save}/{@code of}, for the lines that
 * still have them: 1.19 through 1.20.4.
 *
 * An item on these lines is self-describing NBT, so nothing needs the registries
 * to read it and the server is accepted only to match what the newer lines
 * require. 1.20.5 moved item contents into data components and the pair was
 * dropped for a registry-aware codec, which is itemio-codec.
 */
public final class ItemIo {
	private ItemIo() {
	}

	/** The item written as a tag. */
	public static Tag save(MinecraftServer server, ItemStack stack) {
		return stack.save(new CompoundTag());
	}

	/** The item read back, or {@link ItemStack#EMPTY} when the tag will not parse. */
	public static ItemStack load(MinecraftServer server, Tag tag) {
		if (!(tag instanceof CompoundTag compound)) {
			return ItemStack.EMPTY;
		}

		return ItemStack.of(compound);
	}

	/**
	 * Read a gzipped root tag.
	 *
	 * The byte budget is not honoured on this line: 1.20.2 added the accounter
	 * overload that takes one, and before it the stream read has its own fixed
	 * internal limit. Every caller is reading a payload luna itself wrote, so
	 * the cap is a guard against a corrupt file rather than against an attacker.
	 */
	public static CompoundTag readCompressed(InputStream input, long maxBytes) throws IOException {
		return NbtIo.readCompressed(input);
	}
}
