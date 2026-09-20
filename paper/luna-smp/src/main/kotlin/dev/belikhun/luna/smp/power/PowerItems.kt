package dev.belikhun.luna.smp.power

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * The items of the power line: the three pole parts, the spool a span is
 * strung with, and the wireless node with its two fixtures.
 *
 * This object is also what pulls [PowerCatalog] and [LampCatalog] in - a
 * catalog nobody touches registers no blocks at all, exactly as with the
 * furniture, the flora, the signs and the instruments.
 *
 * There is no item for the wire itself: a span is drawn as a vanilla lead
 * between two client-side entities, so nothing wears a model for it.
 */
@Init(stage = InitStage.PRE_PACK)
object PowerItems {

	/** The item that places each pole part, keyed by block id. */
	val BLOCK_ITEMS: Map<String, NovaItem> = PowerCatalog.BLOCKS.mapValues { (_, block) ->
		LunaSmp.item(block) {}
	}

	/** The spool a builder strings a span with, keyed by tier id. */
	val SPOOLS: Map<String, NovaItem> = PowerCatalog.TIERS.associate { tier ->
		tier.id to LunaSmp.item("power_wire_${tier.id}") {}
	}

	/**
	 * The items that place each wire cover, keyed by block id. Clicked onto
	 * a cable, one takes the cable's place instead of being placed beside it;
	 * see [WireCoverPlacer].
	 */
	val COVER_ITEMS: Map<String, NovaItem> = PowerCatalog.COVER_BLOCKS.mapValues { (_, block) ->
		LunaSmp.item(block) {
			behaviors(WireCoverPlacer)
			lore(
				note("Dây điện đúc thành khối đặc: nối như dây thường, che được, chôn được."),
				note("Chuột phải vào một sợi dây để bọc nó; dây cũ trả về túi."),
				note("Cờ lê vào một mặt để nối hoặc ngắt mặt đó."),
			)
		}
	}

	/** The item that places the wireless power node. */
	val NODE_ITEM: NovaItem = LunaSmp.item(LampCatalog.NODE) {}

	/** The items that place the wireless fixtures, keyed by block id. */
	val LAMP_ITEMS: Map<String, NovaItem> = LampCatalog.LAMP_BLOCKS.mapValues { (_, block) ->
		LunaSmp.item(block) {}
	}

	/** The item that places the light switch. */
	val SWITCH_ITEM: NovaItem = LunaSmp.item(LampCatalog.SWITCH) {}

	/** The item that places the push button. */
	val BUTTON_ITEM: NovaItem = LunaSmp.item(LampCatalog.BUTTON) {}

	/** One line of an item's lore: soft grey, and not the italic a lore line defaults to. */
	private fun note(text: String): Component =
		Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
}
