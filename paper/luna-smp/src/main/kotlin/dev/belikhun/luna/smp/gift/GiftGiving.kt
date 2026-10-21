package dev.belikhun.luna.smp.gift

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior

/**
 * Handing a gift over: holding it and right-clicking another player puts it straight into
 * their main hand, so they are holding it the moment they get it. Whatever they were holding
 * goes into their inventory, or onto the ground at their feet when there is no room.
 */
object GiftGiving : ItemBehavior {

	private val ROSE = TextColor.color(0xff6fa5)

	override fun handleEntityInteract(player: Player, itemStack: ItemStack, clicked: Entity, event: PlayerInteractAtEntityEvent) {
		val receiver = clicked as? Player ?: return

		if (event.hand != EquipmentSlot.HAND || receiver == player) {
			return
		}

		val inHand = player.inventory.itemInMainHand

		if (inHand.type.isAir) {
			return
		}

		event.isCancelled = true

		val gift = inHand.clone()
		gift.amount = 1

		val remaining = inHand.clone()
		remaining.amount -= 1
		player.inventory.setItemInMainHand(if (remaining.amount > 0) remaining else null)

		val held = receiver.inventory.itemInMainHand.clone()
		receiver.inventory.setItemInMainHand(gift)

		if (!held.type.isAir) {
			for (leftover in receiver.inventory.addItem(held).values) {
				receiver.world.dropItem(receiver.location, leftover) { it.pickupDelay = 0 }
			}
		}

		val at = receiver.location.add(0.0, 1.6, 0.0)
		receiver.world.spawnParticle(Particle.HEART, at, 8, 0.4, 0.4, 0.4, 0.0)
		receiver.world.playSound(at, Sound.ENTITY_ITEM_PICKUP, 1f, 1.4f)
		receiver.world.playSound(at, Sound.BLOCK_CHERRY_LEAVES_PLACE, 1f, 1.2f)

		val name = gift.itemMeta?.displayName() ?: Component.translatable(gift.type.translationKey())
		player.sendMessage(Component.text("Bạn đã tặng ", ROSE).append(name).append(Component.text(" cho ${receiver.name} ❤", ROSE)))
		receiver.sendMessage(Component.text("${player.name} tặng bạn ", ROSE).append(name).append(Component.text(" ❤", ROSE)))
	}
}
