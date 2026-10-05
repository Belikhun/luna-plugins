package dev.belikhun.luna.smp.trungthu

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.item.behavior.Consumable

/**
 * The mooncakes: bánh nướng, bánh dẻo and the jelly cakes, crafted from
 * the farm's own crops (recipes in the addon's resources). The part of the
 * Mid-Autumn set that stays on survival; this object is also what pulls
 * [MooncakeCatalog] in, so the plate registers with them.
 */
@Init(stage = InitStage.PRE_PACK)
object MooncakeItems {

	private val SOFT = TextColor.color(0xf3dcb0)

	private fun line(text: String): Component =
		Component.text(text, SOFT).decoration(TextDecoration.ITALIC, false)

	private fun hint(text: String): Component =
		Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)

	private fun effectLine(effect: String?): Component? =
		when (effect) {
			"haste" -> hint("Ăn xong đào nhanh hơn một phút.")
			"speed" -> hint("Ăn xong chạy nhanh hơn một phút.")
			"regen" -> hint("Ăn xong hồi máu một lúc.")
			"absorb" -> hint("Ăn xong có thêm tim vàng một phút.")
			else -> null
		}

	/** The mooncakes, by id: eaten from the hand, or set down on a plate with a sneak-click. */
	val MOONCAKES: Map<String, NovaItem> = TrungThuTable.MOONCAKES.withIndex().associate { (index, cake) ->
		cake.id to LunaSmp.item(cake.id) {
			maxStackSize(16)
			behaviors(
				Consumable(nutrition = cake.nutrition, saturation = cake.saturation, consumeTime = 32),
				MooncakeBehavior(index),
			)
			lore(
				*listOfNotNull(
					line(cake.lore),
					effectLine(cake.effect),
					hint("Shift + chuột phải để bày lên đĩa, cắt làm bốn."),
				).toTypedArray(),
			)

			modelDefinition {
				model = buildModel { createLayeredModel("lunasmp:item/${cake.id}") }
			}
		}
	}

	init {
		// read here so the plate registers even on a build with no other
		// Mid-Autumn object to touch the catalog
		MooncakeCatalog.MOONCAKE_PLATE
	}
}
