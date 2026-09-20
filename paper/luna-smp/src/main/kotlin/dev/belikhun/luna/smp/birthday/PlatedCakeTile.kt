package dev.belikhun.luna.smp.birthday

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
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
import kotlin.math.min

/**
 * A plate of cake set down on a block. Right-click eats it off the plate the
 * way a vanilla cake is eaten, and the empty plate comes back; breaking it gives the
 * plate back as the slice item, since the block has no item of its own.
 */
class PlatedCakeTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private var eaten = false

	init {
		dropProvider {
			if (eaten) {
				emptyList()
			} else {
				listOf(BirthdayItems.CAKE_SLICE.createItemStack())
			}
		}
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND || player.isSneaking || eaten) {
			return false
		}

		eaten = true

		player.foodLevel = min(20, player.foodLevel + NUTRITION)
		player.saturation = min(player.foodLevel.toFloat(), player.saturation + SATURATION)

		val centre = pos.location.add(0.5, 0.4, 0.5)
		pos.world.playSound(centre, "entity.generic.eat", SoundCategory.PLAYERS, 1f, 1f)
		pos.world.playSound(centre, "entity.player.burp", SoundCategory.PLAYERS, 0.6f, 1f)
		pos.world.spawnParticle(Particle.ITEM, centre, 10, 0.2, 0.15, 0.2, 0.05, ItemStack(Material.CAKE))

		val leftover = player.inventory.addItem(BirthdayItems.CAKE_PLATE.createItemStack())

		for (stack in leftover.values) {
			pos.world.dropItemNaturally(centre, stack)
		}

		// breakBlock, not breakBlockNaturally: it returns the drops without
		// spawning them, and an eaten plate must give nothing back but the plate
		runTask {
			if (isEnabled) {
				BlockUtils.breakBlock(
					Context.intention(BlockBreak)
						.param(DefaultContextParamTypes.BLOCK_POS, pos)
						.param(DefaultContextParamTypes.BLOCK_DROPS, false)
						.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
						.build(),
				)
			}
		}

		return true
	}

	private companion object {

		/** The same meal as eating the slice from the hand. */
		const val NUTRITION = 4
		const val SATURATION = 2.4f
	}
}
