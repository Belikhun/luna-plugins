package dev.belikhun.luna.smp.weapons

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * How every piece of the Hoàng đế Nene collection is dressed.
 *
 * The collection is not all one kind of item - five are Nova items and the
 * spear is a vanilla one - so the colours, the story lines and the line that
 * names the set live here rather than in either registration.
 */
object NeneStyle {

	val GOLD: TextColor = TextColor.color(0xFFC64B)
	val EMBER: TextColor = TextColor.color(0xFF7A3D)
	val SOUL: TextColor = TextColor.color(0xC77DFF)
	val ASH: TextColor = TextColor.color(0x8A8FA3)

	/** The line every one of them carries, which is what makes it a set. */
	val COLLECTION: Component = Component.text("Bộ sưu tập ", NamedTextColor.DARK_GRAY)
		.append(Component.text("Hoàng đế Nene", SOUL))
		.decoration(TextDecoration.ITALIC, false)

	/** One line of a weapon's story, unitalicised the way a written lore line should be. */
	fun line(text: String, colour: TextColor = ASH): Component =
		Component.text(text, colour).decoration(TextDecoration.ITALIC, false)

	/** A weapon's name in the collection's gold. */
	fun name(text: String): Component =
		Component.text(text, GOLD).decoration(TextDecoration.ITALIC, false)

	/** The story, spaced off the attributes above and below, ending with the set line. */
	fun lore(story: List<Component>): List<Component> =
		listOf(Component.empty()) + story + listOf(Component.empty(), COLLECTION)
}
