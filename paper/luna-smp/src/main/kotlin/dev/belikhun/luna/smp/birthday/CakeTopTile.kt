package dev.belikhun.luna.smp.birthday

import dev.belikhun.luna.smp.furniture.FurnitureCatalog
import org.bukkit.GameMode
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.format.WorldDataManager

/**
 * The upper block of the cake tower: the top tier, the candles and the
 * sparkler. It owns nothing of the occasion; every click is handed down to
 * the [CakeTile] below it, and it breaks together with it. What it does hold
 * is the candles' state, because the flames are in its model: lit, the
 * displays render at full brightness so the candles read as burning in a
 * dark room.
 */
class CakeTopTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	/** Set while the lower block breaks this one, so this one does not break back. */
	var breakingPartner = false

	val lit: Boolean
		get() = blockState[FurnitureCatalog.LIT] == true

	private val base: CakeTile?
		get() = WorldDataManager.getTileEntity(pos.add(0, -1, 0)) as? CakeTile

	override fun handleEnable() {
		glow()
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		return base?.handleRightClick(ctx) ?: false
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		super.handleBreak(ctx)

		if (breakingPartner) {
			return
		}

		val other = base ?: return
		other.breakingPartner = true

		// the cake item lives on the lower block's drop list, so this break
		// carries the drop verdict down: any non-creative player drops
		val breaker = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player
		val drops = if (breaker != null) {
			breaker.gameMode != GameMode.CREATIVE
		} else {
			ctx[DefaultContextParamTypes.BLOCK_DROPS] == true
		}

		// a tick later: breaking the partner from inside this break's own
		// pipeline re-enters Nova mid-flight
		runTask {
			if (other.isEnabled) {
				BlockUtils.breakBlockNaturally(
					Context.intention(BlockBreak)
						.param(DefaultContextParamTypes.BLOCK_POS, other.pos)
						.param(DefaultContextParamTypes.BLOCK_DROPS, drops)
						.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
						.build(),
				)
			}
		}
	}

	/** Lights or puts out the candles: the model swaps and the displays glow or stop glowing. */
	fun setLit(value: Boolean) {
		if (lit != value) {
			updateBlockState(blockState.with(FurnitureCatalog.LIT, value))
		}

		glow()
	}

	private fun glow() {
		val brightness = if (lit) Display.Brightness(15, 15) else null

		// the display entities are created with the block model, which may not
		// have happened yet when a chunk loads this tile entity
		runTask {
			displayEntities?.forEach { display ->
				display.updateEntityData(true) { this.brightness = brightness }
			}
		}
	}
}
