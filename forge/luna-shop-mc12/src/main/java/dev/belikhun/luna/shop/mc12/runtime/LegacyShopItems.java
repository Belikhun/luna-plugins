package dev.belikhun.luna.shop.mc12.runtime;

import dev.belikhun.luna.legacy.shop.ShopItems;
import dev.belikhun.luna.legacy.string.Strings;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * What the shop needs to know about an item, on 1.12.2.
 *
 * The envelope matches the modern codec in shape - the game's own item tag,
 * gzipped and base64'd, under one root key - but not in content: this line writes
 * an item with {@code writeToNBT} and reads it with the {@code ItemStack(NBT)}
 * constructor, where the modern builds go through a registry-aware codec. So the
 * strings are not interchangeable, and an `items.yml` does not move between game
 * lines. That was already true between paper and fabric; this is a third dialect.
 *
 * A payload that will not decode - a hand-edited file, an item from a mod that is
 * no longer installed - comes back empty rather than throwing, so one bad entry
 * costs one button instead of the whole screen.
 */
public final class LegacyShopItems implements ShopItems<ItemStack> {
	private static final String ROOT_KEY = "item";

	/** NBT tag type 8, the id for a string in a tag list. */
	private static final int TAG_STRING = 8;

	@Override
	public String encode(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}

		try {
			NBTTagCompound root = new NBTTagCompound();

			root.setTag(ROOT_KEY, withCount(stack, 1).writeToNBT(new NBTTagCompound()));

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			CompressedStreamTools.writeCompressed(root, bytes);

			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (Exception ignored) {
			return "";
		}
	}

	@Override
	public ItemStack decode(String encoded) {
		if (Strings.isBlank(encoded)) {
			return empty();
		}

		try {
			byte[] bytes = Base64.getDecoder().decode(encoded);
			NBTTagCompound root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes));

			if (!root.hasKey(ROOT_KEY)) {
				return empty();
			}

			return new ItemStack(root.getCompoundTag(ROOT_KEY));
		} catch (Exception ignored) {
			return empty();
		}
	}

	@Override
	public byte[] fingerprint(ItemStack stack) {
		String encoded = encode(stack);

		return encoded.isEmpty() ? new byte[0] : Base64.getDecoder().decode(encoded);
	}

	@Override
	public ItemStack empty() {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean isEmpty(ItemStack stack) {
		return stack == null || stack.isEmpty();
	}

	/**
	 * Same item, same damage, same tag - and deliberately not the same count.
	 *
	 * `areItemsEqual` covers the item and its damage value, which on this line is
	 * what distinguishes a lime dye from a red one; the tag comparison is what
	 * keeps a named sword from selling as a plain one.
	 */
	@Override
	public boolean sameItemAndData(ItemStack first, ItemStack second) {
		if (isEmpty(first) || isEmpty(second)) {
			return false;
		}

		return ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
	}

	@Override
	public ItemStack withCount(ItemStack stack, int count) {
		if (isEmpty(stack)) {
			return empty();
		}

		ItemStack copy = stack.copy();

		copy.setCount(Math.max(1, count));

		return copy;
	}

	@Override
	public int maxStackSize(ItemStack stack) {
		return isEmpty(stack) ? 1 : Math.max(1, stack.getMaxStackSize());
	}

	/**
	 * The name a player sees.
	 *
	 * Already a plain String on this line - `getDisplayName` returns text, not a
	 * component - so unlike the modern builds there is nothing to flatten.
	 */
	@Override
	public String displayName(ItemStack stack) {
		return isEmpty(stack) ? "" : stack.getDisplayName();
	}

	@Override
	public String itemId(ItemStack stack) {
		if (isEmpty(stack)) {
			return "";
		}

		ResourceLocation id = stack.getItem().getRegistryName();

		return id == null ? "" : id.toString();
	}

	/** Lore lives under `display.Lore` as a list of strings, uncoloured. */
	@Override
	public List<String> lore(ItemStack stack) {
		if (isEmpty(stack) || !stack.hasTagCompound()) {
			return Collections.emptyList();
		}

		NBTTagCompound tag = stack.getTagCompound();

		if (tag == null || !tag.hasKey("display")) {
			return Collections.emptyList();
		}

		NBTTagList lines = tag.getCompoundTag("display").getTagList("Lore", TAG_STRING);
		List<String> out = new ArrayList<String>();

		for (int index = 0; index < lines.tagCount(); index += 1) {
			out.add(lines.getStringTagAt(index));
		}

		return out;
	}
}
