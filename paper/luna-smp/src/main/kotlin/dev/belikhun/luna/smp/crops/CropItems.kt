package dev.belikhun.luna.smp.crops

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
 * The crop items: the packet that plants each crop and the thing it is picked
 * for, plus the withered plant's own item so a builder can place one.
 *
 * Nothing here is written per crop. A new crop is a row in
 * tools/furniture-gen/crops.ts, and this object is also what pulls
 * [CropCatalog] in: a catalog nobody touches registers no blocks at all.
 *
 * The seed packet is the crop's block item, which is what makes planting it
 * the ordinary business of placing a block and lets Nova route the placement
 * through [CropBehavior.canPlace]. It is named separately from its block, so
 * the thing in the hotbar says "Parsnip Seeds" while the thing in the ground
 * says "Parsnip Crop".
 */
@Init(stage = InitStage.PRE_PACK)
object CropItems {

	private val SEASON = TextColor.color(0x8fd0a0)
	private val FACT = TextColor.color(0x9aa4b2)

	private fun line(text: String, colour: TextColor = FACT): Component =
		Component.text(text, colour).decoration(TextDecoration.ITALIC, false)

	/**
	 * The tooltip a packet carries: when it may go in the ground, how long it
	 * takes, and whether it keeps giving.
	 *
	 * The season line is the only one a player strictly needs, since it is the
	 * rule that refuses the planting; the rest is what the game's own shop
	 * would have told them before they bought it.
	 */
	private fun seedLore(spec: CropCatalog.Spec): List<Component> {
		val seasons = spec.seasons.joinToString(", ") { Seasons.label(it) }
		val lore = mutableListOf(
			line("Mùa: $seasons", SEASON),
			line("Chín sau ${spec.maturity} ngày"),
		)

		val regrow = spec.regrow

		if (regrow != null) {
			lore += line("Thu hoạch lại sau $regrow ngày")
		}

		if (spec.count > 1) {
			lore += line("Mỗi lần thu ${spec.count} quả")
		}

		if (spec.trellis) {
			lore += line("Leo giàn: không đi xuyên qua được")
		}

		return lore
	}

	/** The packet that plants each crop, by crop id. */
	val SEEDS: Map<String, NovaItem> = CropCatalog.BLOCKS.mapValues { (id, block) ->
		val spec = CropCatalog.BY_ID.getValue(id)

		LunaSmp.item(block, spec.seedId) {
			maxStackSize(64)
			lore(*seedLore(spec).toTypedArray())

			modelDefinition {
				model = buildModel { createLayeredModel("lunasmp:item/${spec.seedId}") }
			}
		}
	}

	/**
	 * The harvested crop, by crop id.
	 *
	 * A crop the game calls inedible gets no consumable behaviour rather than a
	 * token one: rhubarb, hops and coffee beans are ingredients, and a server
	 * where you can eat a coffee bean is a server where nobody brews one.
	 */
	val PRODUCE: Map<String, NovaItem> = CropCatalog.BY_ID.mapValues { (id, spec) ->
		LunaSmp.item(id) {
			maxStackSize(64)
			lore(line("Giá bán tham khảo: ${spec.price}g"))

			if (spec.nutrition > 0) {
				behaviors(
					Consumable(
						nutrition = spec.nutrition,
						saturation = spec.saturation,
						consumeTime = 28,
					),
				)
			}

			modelDefinition {
				model = buildModel { createLayeredModel("lunasmp:item/$id") }
			}
		}
	}

	/** The withered plant's item, so one can be placed on purpose. */
	val DEAD: NovaItem = LunaSmp.item(CropCatalog.DEAD) {
		maxStackSize(64)
		lore(line("Cây đã chết vì trái mùa.", NamedTextColor.GRAY))
	}

	/** The packet for a crop id, or null if nothing is registered under it. */
	fun seedOf(id: String): NovaItem? = SEEDS[id]

	/** The harvested item for a crop id, or null if nothing is registered under it. */
	fun produceOf(id: String): NovaItem? = PRODUCE[id]
}
