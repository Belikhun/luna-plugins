package dev.belikhun.luna.smp.trungthu

import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import java.util.concurrent.ThreadLocalRandom

/** The pomelo dog yips and gives off a heart when patted. It stores nothing. */
class PomeloDogTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND || player.isSneaking) {
			return false
		}

		val at = pos.location.add(0.5, 0.7, 0.5)
		val pitch = 1.5f + ThreadLocalRandom.current().nextFloat() * 0.3f

		pos.world.playSound(at, "entity.wolf.ambient", SoundCategory.NEUTRAL, 0.5f, pitch)
		pos.world.spawnParticle(Particle.HEART, at.add(0.0, 0.4, 0.0), 1, 0.15, 0.1, 0.15, 0.0)

		return true
	}
}
