package dev.belikhun.luna.smp.birthday

import org.bukkit.GameMode
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity

/**
 * The empty cake stand: the place set for the cake before it arrives.
 *
 * It does one thing. Held against it, the birthday cake item takes the
 * stand's place, facing whoever set it down, and the tower builds itself
 * from there exactly as if it had been placed on the ground; the stand is
 * used up, since the cake stands on its own plate.
 */
class CakeStandTile(
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

		val held = player.inventory.getItem(hand)

		if (held.novaItem != BirthdayItems.CAKE) {
			return false
		}

		// the tower is two blocks tall and the stand is the lower one
		if (!pos.add(0, 1, 0).block.type.isAir) {
			player.sendActionBar(net.kyori.adventure.text.Component.text("Phía trên đế cần trống để đặt bánh."))
			return true
		}

		if (player.gameMode != GameMode.CREATIVE) {
			held.amount -= 1
		}

		val state = BirthdayCatalog.CAKE.defaultBlockState
			.with(DefaultBlockStateProperties.FACING, BirthdayCatalog.facingToward(player.location.direction))

		pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), "block.wool.place", SoundCategory.BLOCKS, 1f, 1f)

		// a tick later: placing over this block from inside its own click
		// re-enters Nova mid-flight. placeBlock removes the stand itself,
		// returning its drops without spawning them, which is the point
		runTask {
			if (isEnabled) {
				BlockUtils.placeBlock(
					Context.intention(BlockPlace)
						.param(DefaultContextParamTypes.BLOCK_POS, pos)
						.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, state)
						.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
						.build(),
				)
			}
		}

		return true
	}
}
