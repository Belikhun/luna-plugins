package dev.belikhun.luna.core.mc.ui;

import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * An item as a string, for the shop's items.yml and anything else that has to
 * write one down.
 *
 * Paper stores these with {@code ItemStack.serializeAsBytes}, which is Bukkit's
 * own wrapper around the same NBT; here it is the game's {@code ItemStack.CODEC}
 * directly, gzipped and base64'd. That is deliberately the one API that has not
 * moved across the supported range: the {@code save}/{@code parse} helpers were
 * dropped in 26.x, the codec was not.
 *
 * Consequence worth knowing: an items.yml written by a Paper backend cannot be
 * copied to a Fabric one, or the reverse. The payloads describe the same item
 * but not in the same envelope, and there is no Bukkit here to read theirs.
 *
 * A payload that will not decode - a hand-edited file, an item from a mod that
 * is no longer installed - comes back {@link ItemStack#EMPTY} rather than
 * throwing, so one bad entry costs one button instead of the whole screen.
 */
public final class LunaItemCodec {
	private static final String ROOT_KEY = "item";
	private static final long MAX_NBT_BYTES = 2L * 1024L * 1024L;

	private LunaItemCodec() {
	}

	/** @return the item as base64, or an empty string when it cannot be written */
	public static String encode(MinecraftServer server, ItemStack stack) {
		if (server == null || stack == null || stack.isEmpty()) {
			return "";
		}

		try {
			DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			Tag encoded = ItemStack.CODEC.encodeStart(ops, stack.copyWithCount(1)).getOrThrow();

			CompoundTag root = new CompoundTag();
			root.put(ROOT_KEY, encoded);

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			NbtIo.writeCompressed(root, bytes);
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (Exception ignored) {
			return "";
		}
	}

	/** @return the item, or {@link ItemStack#EMPTY} when the payload cannot be read */
	public static ItemStack decode(MinecraftServer server, String encoded) {
		if (server == null || encoded == null || encoded.isBlank()) {
			return ItemStack.EMPTY;
		}

		try {
			byte[] bytes = Base64.getDecoder().decode(encoded);
			CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(MAX_NBT_BYTES));
			Tag itemTag = root.get(ROOT_KEY);

			if (itemTag == null) {
				return ItemStack.EMPTY;
			}

			DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			return ItemStack.CODEC.parse(ops, itemTag).result().orElse(ItemStack.EMPTY);
		} catch (Exception ignored) {
			return ItemStack.EMPTY;
		}
	}

	/**
	 * The bytes an id is hashed from.
	 *
	 * Same envelope as {@link #encode}, minus the base64, so two identical items
	 * hash the same and a differently-named one does not.
	 */
	public static byte[] fingerprint(MinecraftServer server, ItemStack stack) {
		String encoded = encode(server, stack);
		return encoded.isEmpty() ? new byte[0] : Base64.getDecoder().decode(encoded);
	}
}
