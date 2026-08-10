package dev.belikhun.luna.core.mc.compat;

import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;

/**
 * An item to and from a tag through {@code ItemStack.CODEC}, for the lines whose
 * codec needs the registries to resolve what it names: 1.20.5 through 26.x.
 *
 * Older lines have the plain {@code save}/{@code of} pair and no registry-aware
 * serialization context, and take itemio-save. The envelope around this - gzip,
 * base64, the size cap, the fingerprint - is the same everywhere and stays in
 * {@link dev.belikhun.luna.core.mc.ui.LunaItemCodec}.
 */
public final class ItemIo {
	private ItemIo() {
	}

	/** The item written as a tag. Throws when the item cannot be encoded. */
	public static Tag save(MinecraftServer server, ItemStack stack) {
		DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);

		return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
	}

	/** The item read back, or {@link ItemStack#EMPTY} when the tag will not parse. */
	public static ItemStack load(MinecraftServer server, Tag tag) {
		DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);

		return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
	}

	/** Read a gzipped root tag, refusing one that would exceed the byte budget. */
	public static CompoundTag readCompressed(InputStream input, long maxBytes) throws IOException {
		return NbtIo.readCompressed(input, NbtAccounter.create(maxBytes));
	}
}
