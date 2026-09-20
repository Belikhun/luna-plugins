package dev.belikhun.luna.smp.birthday

import dev.belikhun.luna.smp.LunaSmp
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.util.item.retrieveData
import xyz.xenondevs.nova.util.item.storeData
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.player.WrappedPlayerInteractEvent

/**
 * The hand-held sparkler. Unlit, a click lights it: the stack in that hand
 * becomes the burning item, stamped with the tick it was lit on. Burning, it
 * throws sparks off its tip every tick it is in either hand, crackles now
 * and then, and after a minute goes out by itself, back to the unlit item; a
 * click while it burns puts it out early.
 *
 * The tip is worked out from where the hand-held transform puts a held
 * item: a little ahead of the eyes, out to the side of the hand, and about
 * level with them, which is where the star of the model ends up.
 */
class SparklerBehavior(private val lit: Boolean) : ItemBehavior {

	override fun handleInteract(player: Player, itemStack: ItemStack, action: Action, wrappedEvent: WrappedPlayerInteractEvent) {
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
			return
		}

		if (player.isSneaking) {
			return
		}

		val hand = wrappedEvent.event.hand ?: EquipmentSlot.HAND

		if (lit) {
			putOut(player, hand, "block.fire.extinguish")
		} else {
			val burning = BirthdayItems.SPARKLER_LIT.createItemStack()
			burning.storeData(LunaSmp, LIT_AT, serverTick)
			player.inventory.setItem(hand, burning)

			val at = tip(player, hand, firstPerson = false)
			player.world.playSound(at, "item.flintandsteel.use", SoundCategory.PLAYERS, 0.8f, 1.1f)
			player.world.spawnParticle(Particle.FIREWORK, at, 8, 0.1, 0.1, 0.1, 0.08)
		}

		wrappedEvent.event.isCancelled = true
		wrappedEvent.actionPerformed = true
	}

	override fun handleEquipmentTick(player: Player, itemStack: ItemStack, slot: EquipmentSlot) {
		if (!lit || (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND)) {
			return
		}

		val litAt = itemStack.retrieveData<Int>(LunaSmp, LIT_AT) ?: serverTick

		if (serverTick - litAt > BURN_TICKS) {
			putOut(player, slot, "block.candle.extinguish")
			return
		}

		// the sparks are the firework particle, small and brief; the electric
		// spark draws as a large cross and one every so often is plenty. The
		// holder sees their own hand in first person and everyone else sees
		// the third-person pose, and the tip is in a different place in each,
		// so the sparks are sent twice: once to the holder at the first-person
		// tip, once to everyone else at the third-person one
		val own = tip(player, slot, firstPerson = true)
		val others = player.world.getNearbyPlayers(player.location, 48.0).filter { it != player }
		val seen = tip(player, slot, firstPerson = false)

		player.spawnParticle(Particle.FIREWORK, own, 1, 0.03, 0.03, 0.03, 0.04)
		player.world.spawnParticle(Particle.FIREWORK, others, player, seen.x, seen.y, seen.z, 1, 0.03, 0.03, 0.03, 0.04, null, true)

		if (serverTick % 8 == 0) {
			player.spawnParticle(Particle.ELECTRIC_SPARK, own, 1, 0.05, 0.05, 0.05, 0.0)
			player.world.spawnParticle(Particle.ELECTRIC_SPARK, others, player, seen.x, seen.y, seen.z, 1, 0.05, 0.05, 0.05, 0.0, null, true)
		}

		if (serverTick % 15 == 0) {
			player.world.playSound(seen, "entity.firework_rocket.twinkle_far", SoundCategory.PLAYERS, 0.25f, 1.6f)
		}
	}

	private fun putOut(player: Player, hand: EquipmentSlot, sound: String) {
		val at = tip(player, hand, firstPerson = false)
		player.inventory.setItem(hand, BirthdayItems.SPARKLER.createItemStack())
		player.world.playSound(at, sound, SoundCategory.PLAYERS, 0.7f, 1f)
		player.world.spawnParticle(Particle.SMOKE, at, 4, 0.03, 0.05, 0.03, 0.01)
	}

	/**
	 * Where the burning end of a held sparkler is in the world, for the two
	 * cameras: the first-person hand holds the item up beside the eyes, the
	 * third-person pose carries it at the hip pointing forward.
	 */
	private fun tip(player: Player, hand: EquipmentSlot, firstPerson: Boolean): Location {
		val eye = player.eyeLocation
		val forward = eye.direction.clone().setY(0).normalize()
		val side = forward.clone().crossProduct(UP).normalize()
		val toward = if (hand == EquipmentSlot.OFF_HAND) -1.0 else 1.0

		return if (firstPerson) {
			eye.clone()
				.add(forward.multiply(0.55))
				.add(side.multiply(0.3 * toward))
				.add(0.0, 0.05, 0.0)
		} else {
			// the hip pose: the stick's tip ends up about level with the belt,
			// which is nine tenths of a block under the eyes
			eye.clone()
				.add(forward.multiply(0.6))
				.add(side.multiply(0.35 * toward))
				.add(0.0, -0.9, 0.0)
		}
	}

	private companion object {

		const val LIT_AT = "sparkler_lit_at"

		/** How long a sparkler burns, in ticks: one minute. */
		const val BURN_TICKS = 20 * 60

		val UP = org.bukkit.util.Vector(0, 1, 0)
	}
}
