package dev.belikhun.luna.core.mc12.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Modern item names translated to 1.12.2's, damage values and all.
 *
 * The flattening in 1.13 turned every coloured and wooden variant into an item of
 * its own: what is `lime_concrete` on a modern backend is `concrete` with damage 5
 * here. Without a translation every luna screen would need a per-line item table,
 * and worse, so would `servers.yml` and a shop's `items.yml` - which are *cluster*
 * config, shared by every backend, and must not fork per game line.
 *
 * So the vocabulary is the modern one everywhere, and this is the only place that
 * knows the old names. Every target below was checked against the 1.12.2 registry
 * rather than written from memory; an unmapped name passes through untouched, so a
 * config may still name a 1.12.2 item directly when it wants to.
 *
 * **The colour orders are not the same, and that is the trap.** Dyes, blocks and
 * banners each have sixteen variants in three different orders: white is dye 15,
 * wool 0 and banner 15. They get separate tables rather than one shared "colour
 * index" that would be quietly wrong for two of them.
 */
public final class LegacyItemNames {
	/** Wool, glass, panes, clay, carpet, concrete, beds: white first, black last. */
	private static final String[] BLOCK_COLOURS = {
		"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
		"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
	};

	/** Dyes: black first, white last, and nothing else about it matches blocks. */
	private static final String[] DYE_COLOURS = {
		"black", "red", "green", "brown", "blue", "purple", "cyan", "light_gray",
		"gray", "pink", "lime", "yellow", "light_blue", "magenta", "orange", "white",
	};

	/**
	 * Banners: the block order, reversed.
	 *
	 * A banner's damage is `15 - colour`, so black is 0 and white is 15 - the exact
	 * inverse of wool. Spelled out rather than computed, because a reader checking
	 * this against the wiki should not have to trust an arithmetic trick.
	 */
	private static final String[] BANNER_COLOURS = {
		"black", "red", "green", "brown", "blue", "purple", "cyan", "light_gray",
		"gray", "pink", "lime", "yellow", "light_blue", "magenta", "orange", "white",
	};

	/** Wood types, in the order `planks`, `sapling`, `wooden_slab` use. */
	private static final String[] WOOD_TYPES = {
		"oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
	};

	/** `<colour>_<modern>` becomes `<legacy>:<block colour index>`. */
	private static final Map<String, String> COLOURED_BLOCKS = new HashMap<String, String>();

	/** `<wood>_<modern>` becomes `<legacy>:<wood index>`. */
	private static final Map<String, String> WOODEN_BLOCKS = new HashMap<String, String>();

	/**
	 * Families that are one block per colour on this line too.
	 *
	 * Nothing about them needs a damage value; the only difference is the colour
	 * word, because 1.12.2 says `silver` where every later version says `light_gray`.
	 */
	private static final Set<String> SEPARATE_PER_COLOUR = new HashSet<String>();

	/** Straight renames, including the ones that carry a fixed damage value. */
	private static final Map<String, String> RENAMED = new HashMap<String, String>();

	static {
		COLOURED_BLOCKS.put("concrete", "concrete");
		COLOURED_BLOCKS.put("concrete_powder", "concrete_powder");
		COLOURED_BLOCKS.put("wool", "wool");
		COLOURED_BLOCKS.put("carpet", "carpet");
		COLOURED_BLOCKS.put("stained_glass", "stained_glass");
		COLOURED_BLOCKS.put("stained_glass_pane", "stained_glass_pane");
		COLOURED_BLOCKS.put("terracotta", "stained_hardened_clay");
		COLOURED_BLOCKS.put("bed", "bed");

		// these two stayed one block per colour rather than becoming damage values,
		// so only the colour word itself has to change
		SEPARATE_PER_COLOUR.add("shulker_box");
		SEPARATE_PER_COLOUR.add("glazed_terracotta");

		WOODEN_BLOCKS.put("planks", "planks");
		WOODEN_BLOCKS.put("sapling", "sapling");
		WOODEN_BLOCKS.put("slab", "wooden_slab");

		// blocks and items that simply changed name
		RENAMED.put("terracotta", "hardened_clay");
		RENAMED.put("cobweb", "web");
		RENAMED.put("magma_block", "magma");
		RENAMED.put("grass_block", "grass");
		RENAMED.put("nether_quartz_ore", "quartz_ore");
		RENAMED.put("oak_door", "wooden_door");
		RENAMED.put("melon", "melon_block");
		RENAMED.put("melon_slice", "melon");
		RENAMED.put("snow_block", "snow");
		RENAMED.put("snow", "snow_layer");
		RENAMED.put("slime_block", "slime");
		RENAMED.put("dandelion", "yellow_flower");
		RENAMED.put("poppy", "red_flower:0");
		RENAMED.put("cornflower", "red_flower:0");
		RENAMED.put("nether_wart", "nether_wart");
		RENAMED.put("nether_wart_block", "nether_wart_block");
		RENAMED.put("smooth_stone", "stone_slab:8");
		RENAMED.put("stone_bricks", "stonebrick");
		RENAMED.put("bricks", "brick_block");
		RENAMED.put("brick", "brick");
		RENAMED.put("nether_bricks", "nether_brick");
		RENAMED.put("end_stone_bricks", "end_bricks");
		RENAMED.put("redstone_torch", "redstone_torch");
		RENAMED.put("repeater", "repeater");
		RENAMED.put("comparator", "comparator");

		// items
		RENAMED.put("firework_rocket", "fireworks");
		RENAMED.put("firework_star", "firework_charge");
		RENAMED.put("enchanted_golden_apple", "golden_apple:1");
		RENAMED.put("experience_bottle", "experience_bottle");
		RENAMED.put("cooked_porkchop", "cooked_porkchop");
		RENAMED.put("porkchop", "porkchop");
		RENAMED.put("cod", "fish:0");
		RENAMED.put("salmon", "fish:1");
		RENAMED.put("tropical_fish", "fish:2");
		RENAMED.put("pufferfish", "fish:3");
		RENAMED.put("gunpowder", "gunpowder");
		RENAMED.put("lapis_lazuli", "dye:4");
		RENAMED.put("cocoa_beans", "dye:3");
		RENAMED.put("ink_sac", "dye:0");
		RENAMED.put("bone_meal", "dye:15");

		// heads and skulls: one item, damage selects which
		RENAMED.put("skeleton_skull", "skull:0");
		RENAMED.put("wither_skeleton_skull", "skull:1");
		RENAMED.put("zombie_head", "skull:2");
		RENAMED.put("player_head", "skull:3");
		RENAMED.put("creeper_head", "skull:4");
		RENAMED.put("dragon_head", "skull:5");
	}

	private LegacyItemNames() {
	}

	/**
	 * The 1.12.2 name for a modern one, with a `:<damage>` suffix where it needs one.
	 *
	 * @param materialName a modern item id, with or without a namespace; already
	 *                     legacy names and explicit damage suffixes pass through
	 */
	public static String translate(String materialName) {
		if (materialName == null || materialName.isEmpty()) {
			return materialName;
		}

		String normalized = materialName.trim().toLowerCase(Locale.ROOT);
		String namespace = "";
		String path = normalized;
		int separator = normalized.indexOf(':');

		// a name that already carries a damage value is the caller being explicit
		if (separator > 0 && isNumber(normalized.substring(separator + 1))) {
			return normalized;
		}

		if (separator > 0) {
			namespace = normalized.substring(0, separator + 1);
			path = normalized.substring(separator + 1);
		}

		String variant = variantOf(path);

		if (variant != null) {
			return namespace + variant;
		}

		String renamed = RENAMED.get(path);

		return renamed == null ? normalized : namespace + renamed;
	}

	/** A `<prefix>_<family>` name as `<legacy family>:<damage>`, or null. */
	private static String variantOf(String path) {
		for (Map.Entry<String, String> family : COLOURED_BLOCKS.entrySet()) {
			String prefix = prefixBefore(path, family.getKey());

			if (prefix == null) {
				continue;
			}

			int index = indexOf(BLOCK_COLOURS, prefix);

			if (index >= 0) {
				return family.getValue() + ":" + index;
			}
		}

		for (Map.Entry<String, String> family : WOODEN_BLOCKS.entrySet()) {
			String prefix = prefixBefore(path, family.getKey());

			if (prefix == null) {
				continue;
			}

			int index = indexOf(WOOD_TYPES, prefix);

			if (index >= 0) {
				return family.getValue() + ":" + index;
			}
		}

		String dye = prefixBefore(path, "dye");

		if (dye != null) {
			int index = indexOf(DYE_COLOURS, dye);

			if (index >= 0) {
				return "dye:" + index;
			}
		}

		String banner = prefixBefore(path, "banner");

		if (banner != null) {
			int index = indexOf(BANNER_COLOURS, banner);

			if (index >= 0) {
				return "banner:" + index;
			}
		}

		for (String family : SEPARATE_PER_COLOUR) {
			String colour = prefixBefore(path, family);

			if (colour != null && indexOf(BLOCK_COLOURS, colour) >= 0) {
				return legacyColourWord(colour) + "_" + family;
			}
		}

		return null;
	}

	/** 1.12.2's word for a colour: only light gray differs, and it is `silver`. */
	private static String legacyColourWord(String colour) {
		return "light_gray".equals(colour) ? "silver" : colour;
	}

	/** What sits before `_<family>` at the end of `path`, or null when it does not. */
	private static String prefixBefore(String path, String family) {
		String suffix = "_" + family;

		if (!path.endsWith(suffix) || path.length() == suffix.length()) {
			return null;
		}

		return path.substring(0, path.length() - suffix.length());
	}

	private static int indexOf(String[] values, String needle) {
		for (int index = 0; index < values.length; index += 1) {
			if (values[index].equals(needle)) {
				return index;
			}
		}

		return -1;
	}

	private static boolean isNumber(String value) {
		if (value.isEmpty()) {
			return false;
		}

		for (int index = 0; index < value.length(); index += 1) {
			if (!Character.isDigit(value.charAt(index))) {
				return false;
			}
		}

		return true;
	}
}
