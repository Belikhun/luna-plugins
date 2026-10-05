package dev.belikhun.luna.smp.trungthu

import org.bukkit.SoundCategory
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.player.WrappedPlayerInteractEvent

/**
 * A light-up plastic sword: two items, switched off and switched on, swapped
 * by a right-click the way the button on the hilt works. Switched on, the
 * blade animates, the sword lights the space round its holder like a carried
 * lantern, hums now and then, and makes its electronic whoosh on every swing
 * and its clash on every hit. It is a toy: a hit does a single point of
 * damage whatever it strikes.
 */
class SwordBehavior(private val swordId: String, private val lit: Boolean) : ItemBehavior {

	override fun handleInteract(player: Player, itemStack: ItemStack, action: Action, wrappedEvent: WrappedPlayerInteractEvent) {
		if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
			if (lit) {
				whoosh(player)
			}

			return
		}

		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
			return
		}

		if (player.isSneaking) {
			return
		}

		val hand = wrappedEvent.event.hand ?: EquipmentSlot.HAND
		player.inventory.setItem(hand, toggled(swordId, lit, itemStack.amount))

		val at = player.location.add(0.0, 1.2, 0.0)

		if (lit) {
			player.world.playSound(at, "block.beacon.deactivate", SoundCategory.PLAYERS, 0.5f, 2.0f)
		} else {
			player.world.playSound(at, "block.beacon.activate", SoundCategory.PLAYERS, 0.5f, 2.0f)
			player.world.playSound(at, "block.note_block.bit", SoundCategory.PLAYERS, 0.6f, 1.6f)
		}

		wrappedEvent.event.isCancelled = true
		wrappedEvent.actionPerformed = true
	}

	override fun handleAttackEntity(player: Player, itemStack: ItemStack, attacked: Entity, event: EntityDamageByEntityEvent) {
		event.damage = TOY_DAMAGE

		if (!lit) {
			return
		}

		val at = attacked.location.add(0.0, 1.0, 0.0)
		player.world.playSound(at, "block.note_block.bit", SoundCategory.PLAYERS, 0.8f, 0.7f)
		player.world.playSound(at, "entity.player.attack.sweep", SoundCategory.PLAYERS, 0.5f, 1.8f)
	}

	override fun handleEquipmentTick(player: Player, itemStack: ItemStack, slot: EquipmentSlot) {
		if (!lit || (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND)) {
			return
		}

		// the same light a carried lantern throws, and never a tune
		HeldLanterns.step(player.uniqueId, player.eyeLocation.block, player.location, null)

		if (serverTick % HUM_TICKS == 0) {
			player.world.playSound(player.location.add(0.0, 1.0, 0.0), "block.beacon.ambient", SoundCategory.PLAYERS, 0.25f, 2.0f)
		}
	}

	private fun whoosh(player: Player) {
		val at = player.location.add(0.0, 1.2, 0.0)
		player.world.playSound(at, "entity.player.attack.sweep", SoundCategory.PLAYERS, 0.35f, 2.0f)
		player.world.playSound(at, "block.note_block.bit", SoundCategory.PLAYERS, 0.4f, 1.3f)
	}

	companion object {

		/** What a plastic sword does to anything it hits. */
		const val TOY_DAMAGE = 1.0

		/** How often a lit sword hums, in ticks. */
		private const val HUM_TICKS = 40

		/** The same sword with its button pressed: lit if it was off, off if it was lit. */
		fun toggled(swordId: String, lit: Boolean, amount: Int = 1): ItemStack {
			val (off, on) = TrungThuItems.SWORDS.getValue(swordId)
			val stack = (if (lit) off else on).createItemStack()
			stack.amount = amount

			return stack
		}
	}
}
