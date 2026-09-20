package dev.belikhun.luna.smp.birthday

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

/**
 * The plushie does one thing when patted: it squeaks and gives off a heart.
 * It is a tile entity only for that click; it stores nothing.
 */
class PlushieTile(
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

		val at = pos.location.add(0.5, 0.9, 0.5)
		val pitch = 1.3f + ThreadLocalRandom.current().nextFloat() * 0.4f

		pos.world.playSound(at, "block.wool.hit", SoundCategory.BLOCKS, 0.8f, pitch)
		pos.world.playSound(at, "entity.rabbit.ambient", SoundCategory.NEUTRAL, 0.4f, pitch + 0.3f)
		pos.world.spawnParticle(Particle.HEART, at.add(0.0, 0.5, 0.0), 1, 0.15, 0.1, 0.15, 0.0)

		return true
	}
}
