package dev.belikhun.luna.smp.trungthu

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.item.retrieveData
import xyz.xenondevs.nova.util.item.storeData
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.player.WrappedPlayerInteractEvent

/**
 * A lantern in the hand: lit, and playing its tune for everyone around.
 *
 * The tune lives on the stack, so a lantern keeps the song it was set to. A
 * click into the air moves on to the next one, the last step switching the
 * music off; a click on a block still places the lantern, because that is
 * the block item's own gesture and this one only answers clicks on nothing.
 */
class LanternBehavior(private val lantern: TrungThuTable.Lantern) : ItemBehavior {

	override fun handleInteract(player: Player, itemStack: ItemStack, action: Action, wrappedEvent: WrappedPlayerInteractEvent) {
		if (action != Action.RIGHT_CLICK_AIR || player.isSneaking) {
			return
		}

		val hand = wrappedEvent.event.hand ?: EquipmentSlot.HAND
		val next = ToyTunes.next(songOf(itemStack))

		player.inventory.setItem(hand, withSong(itemStack, next))

		player.world.playSound(player.location, "block.lever.click", SoundCategory.PLAYERS, 0.4f, 1.8f)
		player.sendActionBar(Component.text("♪ " + ToyTunes.nameOf(next), TUNE_COLOUR))

		wrappedEvent.event.isCancelled = true
		wrappedEvent.actionPerformed = true
	}

	/**
	 * Nova ticks both hands with a clone of the stack. With a lantern in each
	 * hand only the main hand's plays, since one child does not carry two
	 * songs; the light is the same one either way.
	 */
	override fun handleEquipmentTick(player: Player, itemStack: ItemStack, slot: EquipmentSlot) {
		if (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND) {
			return
		}

		if (slot == EquipmentSlot.OFF_HAND && player.inventory.itemInMainHand.novaItem in TrungThuItems.LANTERN_ITEMS) {
			return
		}

		val song = songOf(itemStack)
		HeldLanterns.tick(player, if (song in ToyTunes.SONGS.indices) song else null)
	}

	/** The tune a lantern stack is set to: what a click stored, else the lantern's own first tune. */
	fun songOf(stack: ItemStack): Int = stack.retrieveData<Int>(LunaSmp, SONG_KEY) ?: lantern.song

	companion object {

		private const val SONG_KEY = "lantern_song"

		private val TUNE_COLOUR: TextColor = TextColor.color(0xffc94d)

		/** A copy of a lantern [stack] set to play [song]. */
		fun withSong(stack: ItemStack, song: Int): ItemStack {
			val copy = stack.clone()
			copy.storeData(LunaSmp, SONG_KEY, song)

			return copy
		}
	}
}
