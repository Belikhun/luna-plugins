package dev.belikhun.luna.smp.gift

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * The gift set's items. None of these is sold or crafted; they are handed
 * over.
 *
 * This object is also what pulls [GiftCatalog] in: a catalog nobody touches
 * registers no blocks at all.
 */
@Init(stage = InitStage.PRE_PACK)
object GiftItems {

	private val ROSE = TextColor.color(0xff6fa5)
	private val SOFT = TextColor.color(0xf3c6d8)

	private fun line(text: String, colour: TextColor = SOFT): Component =
		Component.text(text, colour).decoration(TextDecoration.ITALIC, false)

	/**
	 * The bouquet. The slot, the ground and a frame show it standing in its
	 * wrap; in a hand it is carried upright and drawn large, so it is seen
	 * across a room.
	 */
	val BOUQUET: NovaItem = LunaSmp.item(GiftCatalog.BOUQUET) {
		maxStackSize(1)
		behaviors(GiftGiving)
		lore(
			line("Một bó hoa hồng thật to, gói giấy hồng xanh tím, thắt nơ hồng."),
			line("Cầm trên tay, nhấp chuột phải vào người ấy để tặng, hoặc đặt lên bàn cho nở mãi.", NamedTextColor.GRAY),
			line("Dành riêng cho bé ne ❤", ROSE),
		)

		// the flat sprite everywhere an item is drawn; the crossed planes are only the placed block
		modelDefinition {
			model = buildModel { getModel("lunasmp:item/${GiftCatalog.BOUQUET_ID}_held") }
		}
	}
}
