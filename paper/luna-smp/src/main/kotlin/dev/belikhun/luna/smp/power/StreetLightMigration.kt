package dev.belikhun.luna.smp.power

import dev.belikhun.luna.smp.LunaSmp
import net.minecraft.world.level.block.Blocks
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.util.levelChunk
import xyz.xenondevs.nova.util.runTaskLater
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.format.WorldDataManager

/**
 * Gives the lights placed before they ran off a node their tile.
 *
 * Nova creates a tile entity on chunk load only for positions that already
 * carry tile data, and a block that merely gained a tile entity type has
 * none - so every Engineer's Decor light placed under the older build would
 * sit there as a block state with no tile, never registered with a node and
 * never lit. Their block state is unchanged (no property was added, on
 * purpose), so the fix is to hand each one a fresh [WirelessLampTile]
 * through [WorldDataManager.setTileEntity], which also enables it when the
 * chunk is live. A fixture that already has a tile is left alone, so this
 * costs nothing once a chunk has been through it.
 *
 * Finding them without walking every block: every one of them stands in a
 * hitbox block nothing but our own pieces ever put in the world - a resin
 * clump for the flat wall pieces, a structure void for the small ones
 * (lanterns, torches). The chunk's sections are asked whether their palette
 * holds either at all before any block is read, so the common case is a
 * handful of palette checks per chunk and no block reads. The furniture
 * lamps that were tile entities already (table lamps, standing lamps, the
 * ceiling lamp) need none of this: Nova rebuilds their tile from the stored
 * data under whatever class the block now names.
 */
@Init(stage = InitStage.POST_WORLD, runAfter = [WorldDataManager::class])
object StreetLightMigration : Listener {

	/** Ticks after init before the loaded chunks are swept: let the world settle first. */
	private const val SWEEP_DELAY = 20L

	@InitFun
	private fun init() {
		val owner = Bukkit.getPluginManager().getPlugin("LunaSmp")

		if (owner == null || !owner.isEnabled) {
			LunaSmp.logger.warn("plugin half is disabled; street lights placed earlier will not be migrated")

			return
		}

		Bukkit.getPluginManager().registerEvents(this, owner)

		// chunks loaded before this listener existed never fire the event
		runTaskLater(SWEEP_DELAY) {
			var migrated = 0

			for (world in Bukkit.getWorlds()) {
				for (chunk in world.loadedChunks) {
					migrated += migrate(chunk)
				}
			}

			if (migrated > 0) {
				LunaSmp.logger.info("gave {} street light(s) placed before the wireless change their tile", migrated)
			}
		}
	}

	@EventHandler
	private fun handleChunkLoad(event: ChunkLoadEvent) {
		// Nova loads its own chunk data at LOWEST priority, so by NORMAL the
		// block states are readable
		val migrated = migrate(event.chunk)

		if (migrated > 0) {
			LunaSmp.logger.info("gave {} street light(s) in chunk {} {} their tile", migrated, event.chunk.x, event.chunk.z)
		}
	}

	/** Gives every tile-less street light in [chunk] a tile; returns how many. */
	private fun migrate(chunk: Chunk): Int {
		val world = chunk.world
		val level = chunk.levelChunk
		val bottomSection = world.minHeight shr 4
		var migrated = 0

		for ((index, section) in level.sections.withIndex()) {
			if (section.hasOnlyAir() || !section.maybeHas { isHitbox(it) }) {
				continue
			}

			val baseY = (bottomSection + index) shl 4

			for (y in 0 until 16) {
				for (z in 0 until 16) {
					for (x in 0 until 16) {
						if (!isHitbox(section.getBlockState(x, y, z))) {
							continue
						}

						val pos = BlockPos(world, (chunk.x shl 4) + x, baseY + y, (chunk.z shl 4) + z)

						if (adopt(pos)) {
							migrated++
						}
					}
				}
			}
		}

		return migrated
	}

	/** The vanilla blocks a wireless fixture may be standing in. */
	private fun isHitbox(state: net.minecraft.world.level.block.state.BlockState): Boolean =
		state.`is`(Blocks.RESIN_CLUMP) || state.`is`(Blocks.STRUCTURE_VOID)

	/** Gives the block at [pos] a tile if it is a street light without one. */
	private fun adopt(pos: BlockPos): Boolean {
		val state = runCatching { WorldDataManager.getBlockState(pos) }.getOrNull() ?: return false

		if (state.block.id.value() !in LampCatalog.STREET_IDS) {
			return false
		}

		if (WorldDataManager.getTileEntity(pos) != null) {
			return false
		}

		WorldDataManager.setTileEntity(pos, WirelessLampTile(pos, state, Compound()))

		return true
	}
}
