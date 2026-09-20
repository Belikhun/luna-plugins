package dev.belikhun.luna.smp.power

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import xyz.xenondevs.invui.Click
import xyz.xenondevs.invui.item.AbstractItem
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.nova.ui.menu.item.BUTTON_COLORS
import xyz.xenondevs.nova.util.playClickSound

/**
 * The lamp channels, exactly as Nova and its Logistics addon do a cable's
 * channel: one button wearing Nova's own coloured button item for the
 * current channel, left-click for the next, right-click for the previous.
 * The colours are Nova's `BUTTON_COLORS` in Nova's order (red, orange,
 * yellow, green, blue, pink, white), so a channel here looks like a channel
 * there and there are as many of them as Nova has buttons for.
 *
 * A fixture holds one channel; a switch toggles one channel on every node
 * whose reach covers it; a node keeps which channels are off.
 */
object LightChannels {

	/** How many channels there are: one per coloured button Nova ships. */
	val COUNT: Int = BUTTON_COLORS.size

	private val NAMES = listOf("Đỏ", "Cam", "Vàng", "Xanh lá", "Xanh dương", "Hồng", "Trắng")

	/** The MiniMessage colour each channel's name is written in. */
	private val TAGS = listOf("red", "gold", "yellow", "green", "blue", "light_purple", "white")

	fun name(channel: Int): String = NAMES[channel.coerceIn(0, COUNT - 1)]

	/** The channel's name in its own colour, for a lore line or a title. */
	fun coloured(channel: Int): String {
		val index = channel.coerceIn(0, COUNT - 1)

		return "<${TAGS[index]}>${NAMES[index]}</${TAGS[index]}>"
	}

	/**
	 * The one channel button. [current] says which channel is set, [choose]
	 * is called with the new one, and [describe] adds the line the menu
	 * wants under the name (a switch shows whether the channel is on).
	 */
	fun button(
		current: () -> Int,
		choose: (Int) -> Unit,
		describe: (Int) -> String? = { null },
	): AbstractItem = object : AbstractItem() {

		override fun getItemProvider(player: Player): ItemProvider {
			val channel = current().coerceIn(0, COUNT - 1)
			val builder = BUTTON_COLORS[channel].createClientsideItemBuilder()
				.setName("<white>Kênh: <white>${channel + 1}</white> ${coloured(channel)}</white>")

			val extra = describe(channel)

			if (extra != null) {
				builder.addLoreLines(extra)
			}

			builder.addLoreLines("<gray>Chuột trái: kênh tiếp · chuột phải: kênh trước</gray>")

			return builder
		}

		override fun handleClick(clickType: ClickType, player: Player, click: Click) {
			val channel = current()

			val next = when (clickType) {
				ClickType.LEFT -> (channel + 1).mod(COUNT)
				ClickType.RIGHT -> (channel - 1).mod(COUNT)
				else -> return
			}

			choose(next)
			notifyWindows()
			player.playClickSound()
		}
	}
}
