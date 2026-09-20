package dev.belikhun.luna.smp

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Levelled

/**
 * The companion light block a fixture drops into a neighbouring space.
 *
 * Nothing luna places can emit light by itself: Nova has no light behaviour,
 * so a fixture that wants to light a room either stands in a vanilla block
 * that emits (which means hiding that block from every player who owns one -
 * the copper bulb mistake) or puts a `minecraft:light` next to itself. This
 * is the second way, and it costs the world nothing: the light block is
 * air-like, generates nowhere and is not something a player can place.
 *
 * The space is not reserved. A light block is replaceable, so anything built
 * into it takes it, which is why a lit fixture re-places its light every tick
 * and a dark one takes it back.
 */
object LightSource {

	/** Puts a light of [level] in [block], if that space is free. */
	fun place(block: Block, level: Int) {
		if (block.type == Material.LIGHT || !block.type.isAir) {
			return
		}

		block.type = Material.LIGHT

		val data = block.blockData as Levelled
		data.level = level
		block.blockData = data
	}

	/**
	 * Swaps a leftover copper bulb backing at [block] for the barrier the
	 * fixtures stand in now.
	 *
	 * The light panel, the alarm base and the wireless light block used to be
	 * backed by a waxed copper bulb. Nova writes a hitbox only when a block is
	 * placed or its state changes, never on chunk load, so a fixture placed
	 * under an older build keeps its bulb - and now that the pack hides no
	 * bulb at all, that bulb renders inside the fixture's own model.
	 *
	 * Every fixture calls this as it enables and again on its tick. Enabling
	 * alone is not enough twice over: a chunk sweep misses whatever was
	 * already loaded when it ran, and on a fresh placement Nova writes the
	 * hitbox *after* the tile enables, so the enable-time call loses the race.
	 *
	 * The tick call is what makes this hold against a stale backing of any
	 * origin, including Nova's own: the block a fixture stands in is baked
	 * into `ResourceLookups.BLOCK_MODEL` during the resource pack build and
	 * persisted, so an edited stateSelector is invisible until that lookup is
	 * regenerated. Anything that is not a bulb is left exactly as it is.
	 */
	fun retireBulbBacking(block: Block) {
		if (block.type != Material.WAXED_COPPER_BULB) {
			return
		}

		block.setType(Material.BARRIER, false)
	}

	/** Takes back the light in [block], leaving anything else alone. */
	fun clear(block: Block) {
		if (block.type != Material.LIGHT) {
			return
		}

		block.type = Material.AIR
	}
}
