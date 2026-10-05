package dev.belikhun.luna.smp.trungthu

import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity

/**
 * The lion-dance drum. A click plays the beat a lion dances to - "tùng tùng
 * cắc", the big drum, the rim struck with the stick, the cymbals crashing on
 * the turn - eight bars, ending on a roll; a click while it plays stops it.
 *
 * The beat is written one character per sixteenth: `T` the drum's head, `c`
 * the rim, `x` the cymbals, `.` a rest.
 */
class DrumTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private var started = -1

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND || player.isSneaking) {
			return false
		}

		toggle()

		return true
	}

	/** Whether the beat is going. */
	val playing: Boolean
		get() = started >= 0

	/** Starts the beat from the top, or stops it. */
	fun toggle() {
		started = if (started >= 0) -1 else serverTick
	}

	override fun handleTick() {
		if (started < 0) {
			return
		}

		val elapsed = serverTick - started

		if (elapsed % STEP != 0) {
			return
		}

		val step = elapsed / STEP

		if (step >= BEAT.length) {
			started = -1
			return
		}

		val at = pos.location.add(0.5, 1.0, 0.5)
		val world = pos.world

		when (BEAT[step]) {
			'T' -> {
				world.playSound(at, "block.note_block.basedrum", SoundCategory.RECORDS, 2.5f, 0.6f)
				world.playSound(at, "block.note_block.basedrum", SoundCategory.RECORDS, 1.2f, 0.5f)
				world.spawnParticle(Particle.NOTE, at.clone().add(0.0, 0.2, 0.0), 0, 0.0, 0.0, 0.0, 1.0)
			}

			'c' -> world.playSound(at, "block.note_block.hat", SoundCategory.RECORDS, 1.6f, 1.4f)

			'x' -> {
				world.playSound(at, "block.note_block.snare", SoundCategory.RECORDS, 1.2f, 0.6f)
				world.playSound(at, "block.anvil.land", SoundCategory.RECORDS, 0.2f, 2.0f)
				world.spawnParticle(Particle.CRIT, at, 6, 0.3, 0.1, 0.3, 0.1)
			}
		}
	}

	companion object {

		/** Ticks per sixteenth: a crotchet of eight ticks, a brisk dance. */
		const val STEP = 2

		private const val A = "T...T.c.T.c.T.x."
		private const val B = "T.T.c.T.T.c.x.x."
		private const val ROLL = "T.T.T.T.TTTTx..."

		val BEAT: String = A + B + A + B + A + B + A + ROLL
	}
}
