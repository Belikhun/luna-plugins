package dev.belikhun.luna.smp.trungthu

import org.bukkit.GameMode
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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
 * What a mooncake does besides being eaten.
 *
 * Like the birthday cake's slice it is a plain consumable, never a block
 * item: an item that places a block and is also eaten starts both on the
 * same click. A sneak-click on a block sets it down on a plate instead, and
 * the eat the same click would start is refused while sneaking. On the
 * plate it is cut in four and eaten a quarter at a time.
 */
class MooncakeBehavior(private val index: Int) : ItemBehavior {

	private val cake: TrungThuTable.Mooncake = TrungThuTable.MOONCAKES[index]

	override fun handleInteract(player: Player, itemStack: ItemStack, action: Action, wrappedEvent: WrappedPlayerInteractEvent) {
		if (!player.isSneaking) {
			return
		}

		val event = wrappedEvent.event

		if (action == Action.RIGHT_CLICK_AIR) {
			event.isCancelled = true
			return
		}

		if (action != Action.RIGHT_CLICK_BLOCK) {
			return
		}

		val clicked = event.clickedBlock ?: return
		val target = clicked.getRelative(event.blockFace)

		if (!placeOn(target, MooncakeCatalog.facingToward(player.location.direction), index)) {
			return
		}

		if (player.gameMode != GameMode.CREATIVE) {
			itemStack.amount -= 1
		}

		target.world.playSound(target.location.add(0.5, 0.3, 0.5), "block.decorated_pot.place", SoundCategory.BLOCKS, 0.8f, 1.3f)
		event.isCancelled = true
		wrappedEvent.actionPerformed = true
	}

	override fun handleConsume(player: Player, itemStack: ItemStack, event: PlayerItemConsumeEvent) {
		applyEffect(player, cake.effect, 1.0)
	}

	companion object {

		/** Sets mooncake [index] down on a plate in [target], if that space is free. */
		fun placeOn(target: Block, facing: BlockFace, index: Int): Boolean {
			if (!target.type.isAir) {
				return false
			}

			val state = MooncakeCatalog.MOONCAKE_PLATE.defaultBlockState
				.with(DefaultBlockStateProperties.FACING, facing)
				.with(MooncakeCatalog.VARIANT, index)

			return BlockUtils.placeBlock(
				Context.intention(BlockPlace)
					.param(DefaultContextParamTypes.BLOCK_POS, target.pos)
					.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, state)
					.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
					.build(),
			)
		}

		/**
		 * The little extra a mooncake leaves behind, scaled by [share] (a quarter
		 * off the plate gives a quarter of it). Effect types are only named
		 * here, at eating time, never while the items register.
		 */
		fun applyEffect(player: Player, effect: String?, share: Double) {
			val potion = when (effect) {
				"haste" -> PotionEffect(PotionEffectType.HASTE, (20 * 60 * share).toInt(), 0, false, true, true)
				"speed" -> PotionEffect(PotionEffectType.SPEED, (20 * 60 * share).toInt(), 0, false, true, true)
				"regen" -> PotionEffect(PotionEffectType.REGENERATION, (20 * 10 * share).toInt().coerceAtLeast(60), 0, false, true, true)
				"absorb" -> PotionEffect(PotionEffectType.ABSORPTION, (20 * 60 * share).toInt(), 0, false, true, true)
				else -> null
			} ?: return

			player.addPotionEffect(potion)
		}
	}
}
