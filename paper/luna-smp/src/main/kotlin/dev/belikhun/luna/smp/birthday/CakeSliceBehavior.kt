package dev.belikhun.luna.smp.birthday

import org.bukkit.GameMode
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.player.WrappedPlayerInteractEvent
import xyz.xenondevs.nova.world.pos

/**
 * What the plate of cake does besides being eaten.
 *
 * The slice is a plain consumable, never a block item: an item that is both
 * places its block AND starts being eaten on the same click, because the
 * client sends the use-on-block packet and then the use packet, and each
 * side acts on one of them. So the plate is set down with a sneak-click
 * instead, and the eat that the same click would start is refused while
 * sneaking. Eating it, from the hand or off the plate, gives an empty plate
 * back.
 */
object CakeSliceBehavior : ItemBehavior {

	override fun handleInteract(player: Player, itemStack: ItemStack, action: Action, wrappedEvent: WrappedPlayerInteractEvent) {
		if (!player.isSneaking) {
			return
		}

		val event = wrappedEvent.event

		// the use packet that follows a sneak-click on a block would start
		// eating the next slice; sneaking is the set-down gesture, not a meal
		if (action == Action.RIGHT_CLICK_AIR) {
			event.isCancelled = true
			return
		}

		if (action != Action.RIGHT_CLICK_BLOCK) {
			return
		}

		val clicked = event.clickedBlock ?: return
		val target = clicked.getRelative(event.blockFace)

		if (!target.type.isAir) {
			return
		}

		val state = BirthdayCatalog.PLATED_CAKE.defaultBlockState
			.with(DefaultBlockStateProperties.FACING, BirthdayCatalog.facingToward(player.location.direction))

		val placed = BlockUtils.placeBlock(
			Context.intention(BlockPlace)
				.param(DefaultContextParamTypes.BLOCK_POS, target.pos)
				.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, state)
				.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
				.build(),
		)

		if (!placed) {
			return
		}

		if (player.gameMode != GameMode.CREATIVE) {
			itemStack.amount -= 1
		}

		target.world.playSound(target.location.add(0.5, 0.5, 0.5), "block.wool.place", SoundCategory.BLOCKS, 0.8f, 1.2f)
		event.isCancelled = true
		wrappedEvent.actionPerformed = true
	}

	/**
	 * The plate comes back into the inventory rather than as the event's
	 * replacement: a replacement stands in for the whole stack in hand, which
	 * is how a stack of plates once turned into a single bowl.
	 */
	override fun handleConsume(player: Player, itemStack: ItemStack, event: PlayerItemConsumeEvent) {
		val plate = BirthdayItems.CAKE_PLATE.createItemStack()
		val leftover = player.inventory.addItem(plate)

		for (stack in leftover.values) {
			player.world.dropItemNaturally(player.location, stack)
		}
	}
}

/**
 * A chocolate bar's little extra: a short effect on top of the food. The
 * effect type is only named here, at eating time, never while the item is
 * being registered, because the effect registry is not there yet at that
 * stage.
 */
class ChocolateBehavior(private val effect: () -> PotionEffect) : ItemBehavior {

	override fun handleConsume(player: Player, itemStack: ItemStack, event: PlayerItemConsumeEvent) {
		player.addPotionEffect(effect())
	}

	companion object {

		/** Dark chocolate: a minute of speed. */
		val DARK = ChocolateBehavior { PotionEffect(PotionEffectType.SPEED, 20 * 60, 0, false, true, true) }

		/** Milk chocolate: ten seconds of regeneration. */
		val MILK = ChocolateBehavior { PotionEffect(PotionEffectType.REGENERATION, 20 * 10, 0, false, true, true) }
	}
}
